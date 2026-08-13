/**
 * Mitsubishi Comfort Cloud — parent app.
 * Ports Kumo Cloud V3 API behavior from smack000/comfort_HA for Hubitat Elevation.
 *
 * Hubitat notes:
 *  - runIn(delay, "method", [data: map, overwrite: bool])
 *  - Use @Field for module-level constants
 *  - Prefer asynchttp* at runtime; sync HTTP only during setup pages
 */
import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import hubitat.helper.HexUtils

definition(
    name: "Mitsubishi Comfort",
    namespace: "ephrayim",
    author: "ephrayim",
    description: "Mitsubishi Comfort / Kumo Cloud HVAC with hybrid local LAN control",
    category: "Integrations",
    menu: "Integrations",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat"
)

@Field static final String API_BASE = "https://app-prod.kumocloud.com"
@Field static final String SOCKET_BASE = "https://socket-prod.kumocloud.com"
@Field static final String API_VERSION = "v3"
@Field static final String API_APP_VERSION = "3.2.4"
@Field static final Long TOKEN_TTL_MS = 20L * 60L * 1000L
@Field static final Long TOKEN_MARGIN_MS = 5L * 60L * 1000L
@Field static final Long COMMAND_TTL_MS = 60L * 1000L
@Field static final Long COMMAND_GAP_MS = 5000L
@Field static final Integer STALE_FAIL_THRESHOLD = 3
@Field static final Integer LOCAL_FAIL_THRESHOLD = 3
@Field static final Long LOCAL_DEGRADE_MS = 5L * 60L * 1000L
@Field static final Long OFFLINE_PROBE_MS = 10L * 60L * 1000L
@Field static final Integer LOCAL_HTTP_TIMEOUT = 8
@Field static final Integer CRYPTO_SERIAL_MIN_BYTES = 9
@Field static final String LOCAL_STATUS_QUERY = '{"c":{"indoorUnit":{"status":{}}}}'
@Field static final String LOCAL_PROFILE_QUERY = '{"c":{"indoorUnit":{"profile":{}}}}'
@Field static final String LOCAL_ADAPTER_STATUS_QUERY = '{"c":{"adapter":{"status":{}}}}'
@Field static final String LOCAL_ADAPTER_INFO_QUERY = '{"c":{"adapter":{"info":{}}}}'
@Field static final String LOCAL_MHK2_QUERY = '{"c":{"mhk2":{"status":{}}}}'
@Field static final String W_PARAM_HEX = "44c73283b498d432ff25f5c8e06a016aef931e68f0a00ea710e36e6338fb22db"

@Field static final Map F_TO_C = [
    61: 16.0, 62: 16.5, 63: 17.0, 64: 17.5, 65: 18.0, 66: 18.5,
    67: 19.5, 68: 20.0, 69: 21.0, 70: 21.5, 71: 22.0, 72: 22.5,
    73: 23.0, 74: 23.5, 75: 24.0, 76: 24.5, 77: 25.0, 78: 25.5,
    79: 26.0, 80: 26.5
]

@Field static final Map C_TO_F = [
    16.0: 61, 16.5: 62, 17.0: 63, 17.5: 64, 18.0: 65, 18.5: 66,
    19.0: 67, 19.5: 67, 20.0: 68, 20.5: 69,
    21.0: 69, 21.5: 70, 22.0: 71, 22.5: 72,
    23.0: 73, 23.5: 74, 24.0: 75, 24.5: 76, 25.0: 77, 25.5: 78,
    26.0: 79, 26.5: 80
]

@Field static final Map API_TO_UI_FAN = [
    "auto": "auto", "superQuiet": "quiet", "quiet": "low", "low": "medium",
    "powerful": "high", "superPowerful": "powerful"
]
@Field static final Map UI_TO_API_FAN = [
    "auto": "auto", "quiet": "superQuiet", "low": "quiet", "medium": "low",
    "high": "powerful", "powerful": "superPowerful"
]

@Field static final Map API_TO_UI_VANE = [
    "auto": "auto", "swing": "swing", "vertical": "lowest", "midvertical": "low",
    "midpoint": "middle", "midhorizontal": "high", "horizontal": "highest"
]
@Field static final Map UI_TO_API_VANE = [
    "auto": "auto", "swing": "swing", "lowest": "vertical", "low": "midvertical",
    "middle": "midpoint", "high": "midhorizontal", "highest": "horizontal"
]

@Field static final Map KUMO_TO_HUB_MODE = [
    "off": "off", "cool": "cool", "heat": "heat", "dry": "dry", "vent": "fan",
    "auto": "auto", "autoCool": "auto", "autoHeat": "auto"
]
@Field static final Map HUB_TO_KUMO_MODE = [
    "off": "off", "cool": "cool", "heat": "heat", "dry": "dry", "fan": "vent", "auto": "auto"
]

@Field static final List UI_FAN_ORDER = ["auto", "quiet", "low", "medium", "high", "powerful"]

@Field static final Map FAN_SPEEDS_BY_COUNT = [
    2: ["low", "high"],
    3: ["low", "medium", "high"],
    4: ["quiet", "low", "medium", "high"],
    5: ["quiet", "low", "medium", "high", "powerful"]
]

preferences {
    page(name: "mainPage")
    page(name: "cleanupPage")
}

def mainPage() {
    def credKey = "${settings.username ?: ''}:${settings.password ? 'set' : ''}"
    if (state.lastCredKey && state.lastCredKey != credKey) {
        state.setupSitesLoaded = false
        state.remove("setupSiteChoices")
        state.remove("loginError")
    }
    state.lastCredKey = credKey

    if (settings.username && settings.password && !state.setupSitesLoaded) {
        loadSetupSites()
    }

    dynamicPage(name: "mainPage", title: "Mitsubishi Comfort", install: true, uninstall: true) {
        section("Comfort Cloud credentials") {
            input name: "username", type: "text", title: "Email", required: true, submitOnChange: true
            input name: "password", type: "password", title: "Password", required: true, submitOnChange: true
            if (state.loginError) {
                paragraph "Login error: ${state.loginError}"
            } else if (state.setupSitesLoaded) {
                paragraph "Login successful. Select your site below."
            }
        }
        section("Site") {
            if (state.setupSiteChoices) {
                input name: "siteId", type: "enum", title: "Site", options: state.setupSiteChoices, required: true
            } else {
                paragraph "Enter credentials above, then tap Done or reopen this page to load sites."
            }
        }
        section("Polling") {
            input name: "pollInterval", type: "enum", title: "Device poll interval", options: [
                "60": "1 minute",
                "300": "5 minutes",
                "600": "10 minutes"
            ], defaultValue: "60", required: true
        }
        section("Local control") {
            input name: "preferLocal", type: "bool", title: "Prefer local LAN control", defaultValue: true
            input name: "allowOffline", type: "bool", title: "Allow offline local control when internet is down", defaultValue: true
            input name: "enableSubnetScan", type: "bool", title: "Scan subnet for unit IPs (optional)", defaultValue: false
            input name: "subnetOverride", type: "text", title: "Subnet to scan (e.g. 192.168.1)", required: false
            if (state.offlineReady) {
                paragraph "Offline ready: cached credentials and IPs allow local control without internet."
            } else if (state.knownSerials instanceof List && !state.knownSerials.isEmpty()) {
                paragraph "Offline not ready: enter unit IPs below and ensure cloud login has fetched local passwords."
            }
            if (state.cloudOffline) {
                paragraph "Operating in offline local-only mode (cloud unreachable)."
            }
            if (state.lastCloudContact) {
                paragraph "Last cloud contact: ${state.lastCloudContact}"
            }
            if (state.zoneIndex instanceof Map && !state.zoneIndex.isEmpty()) {
                state.zoneIndex.each { serial, info ->
                    def ipKey = unitIpSettingKey(serial as String)
                    def title = "${info?.zoneName ?: serial} IP"
                    def cur = state.localCreds?.get(serial)?.address
                    if (cur) title = "${title} (current: ${cur})"
                    input name: ipKey, type: "text", title: title, required: false
                }
            }
            input name: "btnRefreshCreds", type: "button", title: "Refresh local credentials"
            input name: "btnRediscoverIp", type: "button", title: "Re-discover local IPs"
        }
        section("Diagnostics") {
            input name: "debugLogging", type: "bool", title: "Debug logging (auto-off after 30 min)", defaultValue: false
            href name: "cleanupPage", title: "Stale child cleanup", description: "List thermostats no longer on your account"
        }
    }
}

def cleanupPage() {
    dynamicPage(name: "cleanupPage", title: "Stale child cleanup", uninstall: false, install: false) {
        section("Thermostats marked stale") {
            def stale = getStaleChildren()
            if (!stale || stale.isEmpty()) {
                paragraph "No stale thermostats. Stale devices are zone thermostats whose zone no longer appears on your Comfort Cloud account."
            } else {
                paragraph "The following thermostats are marked stale. Remove the thermostat from the Devices page if you no longer need it; nested indoor, filter, diagnostics, and wireless components are removed with it."
                stale.each { child ->
                    paragraph "${child.label ?: child.name} — DNI: ${child.deviceNetworkId}"
                }
            }
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case "btnRefreshCreds":
            if (tokenIsFresh()) {
                startSocketIoPasswordFetch()
            } else {
                log.warn "Cannot refresh local credentials: cloud token is not fresh"
            }
            break
        case "btnRediscoverIp":
            startIpDiscovery()
            break
    }
}

def installed() {
    log.info "Mitsubishi Comfort installed"
    initializeApp(true)
}

def updated() {
    log.info "Mitsubishi Comfort updated"
    syncManualIpSettings()
    initializeApp(false)
}

def uninstalled() {
    log.info "Mitsubishi Comfort uninstalled"
    unschedule()
    getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}

def initializeApp(Boolean fullInit) {
    unschedule()
    state.pollingScheduled = false
    ensureStateMaps()
    try {
        ensureAllZoneChildren()
        logDebug "Zone children ensured for ${(state.zoneIndex ?: [:]).size()} zone(s)"
        (state.knownSerials ?: []).each { serial ->
            if (state.deviceData[serial] instanceof Map) {
                pushStateToChildren(serial as String)
            }
        }
    } catch (Exception e) {
        log.error "Failed to ensure/publish zone children: ${e.message}"
    }

    if (settings.debugLogging) {
        runIn(1800, "debugOff", [overwrite: true])
        log.info "Mitsubishi Comfort debug logging enabled"
    }

    if (!settings.username || !settings.password || !settings.siteId) {
        log.warn "Mitsubishi Comfort: credentials or site not configured"
        return
    }

    syncManualIpSettings()
    recomputeOfflineReady()

    if (fullInit && state.setupAccessToken) {
        atomicState.accessToken = state.setupAccessToken
        atomicState.refreshToken = state.setupRefreshToken
        atomicState.tokenExpiresAt = now() + TOKEN_TTL_MS
        state.remove("setupAccessToken")
        state.remove("setupRefreshToken")
    }

    if (!tokenIsFresh() && offlineOperationAllowed()) {
        state.cloudOffline = true
        onOfflineBoot(fullInit ? "initialDiscover" : "resume")
        return
    }

    runtimeLogin(fullInit ? "initialDiscover" : "resume")
}

def debugOff() {
    app.updateSetting("debugLogging", [type: "bool", value: false])
    log.info "Mitsubishi Comfort debug logging disabled"
}

def ensureStateMaps() {
    if (!(state.deviceData instanceof Map)) state.deviceData = [:]
    if (!(state.profiles instanceof Map)) state.profiles = [:]
    if (!(state.statusData instanceof Map)) state.statusData = [:]
    if (!(state.notificationData instanceof Map)) state.notificationData = [:]
    if (!(state.wirelessData instanceof Map)) state.wirelessData = [:]
    if (!(state.zoneIndex instanceof Map)) state.zoneIndex = [:]
    if (!(state.commandCache instanceof Map)) state.commandCache = [:]
    if (!(state.commandPending instanceof Map)) state.commandPending = [:]
    if (!(state.commandInFlight instanceof Map)) state.commandInFlight = [:]
    if (!(state.lastCommandSend instanceof Map)) state.lastCommandSend = [:]
    if (!(state.failCounts instanceof Map)) state.failCounts = [:]
    if (!(state.lastCommandFields instanceof Map)) state.lastCommandFields = [:]
    if (!(state.pendingHttpQueue instanceof List)) state.pendingHttpQueue = []
    if (!(state.knownSerials instanceof List)) state.knownSerials = []
    if (!(state.localCreds instanceof Map)) state.localCreds = [:]
    if (!(state.localInFlight instanceof Map)) state.localInFlight = [:]
    if (!(state.localFailCounts instanceof Map)) state.localFailCounts = [:]
    if (!(state.localDegradedUntil instanceof Map)) state.localDegradedUntil = [:]
    if (!(state.lastConnectionPath instanceof Map)) state.lastConnectionPath = [:]
    if (!(state.ipProbeQueue instanceof List)) state.ipProbeQueue = []
    if (!(state.ipProbeActive instanceof Map)) state.ipProbeActive = [:]
}

// --- Setup-time synchronous auth ---

def loadSetupSites() {
    state.loginError = null
    try {
        def loginResp = null
        httpPost([
            uri: "${API_BASE}/${API_VERSION}/login",
            headers: baseHeaders(),
            body: JsonOutput.toJson([
                username: settings.username,
                password: settings.password,
                appVersion: API_APP_VERSION
            ]),
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: 30
        ]) { response -> loginResp = response }

        if (!loginResp || (loginResp.status as int) != 200) {
            state.loginError = loginResp ? "HTTP ${loginResp.status}" : "No response"
            return
        }

        def loginJson = parseMaybeJson(loginResp.data)
        if (!loginJson?.token?.access) {
            state.loginError = "Login response missing token"
            return
        }
        state.setupAccessToken = loginJson.token.access
        state.setupRefreshToken = loginJson.token.refresh

        def sitesResp = null
        httpGet([
            uri: "${API_BASE}/${API_VERSION}/sites/",
            headers: authHeaders(state.setupAccessToken),
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: 30
        ]) { response -> sitesResp = response }

        if (!sitesResp || (sitesResp.status as int) != 200) {
            state.loginError = sitesResp ? "Sites HTTP ${sitesResp.status}" : "No sites response"
            return
        }

        def sites = parseMaybeJson(sitesResp.data)
        if (!(sites instanceof List)) {
            state.loginError = "Unexpected sites response"
            return
        }

        def choices = [:]
        sites.each { site ->
            if (site?.id) {
                choices[(site.id as String)] = (site.name ?: "Site ${site.id}") as String
            }
        }
        state.setupSiteChoices = choices
        state.setupSitesLoaded = true
    } catch (Exception e) {
        state.loginError = e.message
        log.error "Setup login failed: ${e.message}"
    }
}

// --- Runtime auth ---

def runtimeLogin(String nextStep) {
    state.authNextStep = nextStep
    if (state.cloudOffline && offlineOperationAllowed()) {
        onOfflineBoot(nextStep)
        return
    }
    if (tokenIsFresh()) {
        onAuthSuccess()
        return
    }
    if (atomicState.refreshToken) {
        refreshAccessToken()
    } else {
        loginWithPassword()
    }
}

def onOfflineBoot(String nextStep) {
    log.warn "Mitsubishi Comfort: cloud offline — using local-only mode"
    if (!state.pollingScheduled) {
        schedulePolling()
        state.pollingScheduled = true
    }
    if (stepNeedsLocalPoll(nextStep)) {
        refreshAllDeviceDetailsLocal()
    }
    scheduleOfflineCloudProbe()
}

def tokenIsFresh() {
    return atomicState.accessToken && atomicState.tokenExpiresAt &&
        ((atomicState.tokenExpiresAt as long) > (now() + TOKEN_MARGIN_MS))
}

def stepNeedsLocalPoll(String step) {
    return step in ["initialDiscover", "resume"]
}

def onAuthSuccess() {
    state.cloudOffline = false
    state.lastCloudContact = new Date().format("yyyy-MM-dd HH:mm:ss")
    def step = state.authNextStep
    state.authNextStep = null
    logDebug "Auth success, nextStep=${step}"

    if (!state.pollingScheduled) {
        schedulePolling()
        state.pollingScheduled = true
    }
    scheduleOfflineCloudProbe()

    // Replay any requests deferred for auth, then continue with discovery/resume.
    if (state.pendingHttpQueue && !state.pendingHttpQueue.isEmpty()) {
        def queue = state.pendingHttpQueue as List
        state.pendingHttpQueue = []
        queue.each { pending ->
            if (pending?.ctx?.requestType == "command" && pending.ctx.serial) {
                state.commandInFlight[pending.ctx.serial] = true
                if (pending.body?.commands instanceof Map) {
                    state.lastCommandFields[pending.ctx.serial] = pending.body.commands.keySet() as List
                }
            }
            if (pending.method == "GET") {
                apiGet(pending.path as String, (pending.ctx ?: [:]) as Map)
            } else {
                apiPost(pending.path as String, (pending.body ?: [:]) as Map, (pending.ctx ?: [:]) as Map)
            }
        }
    }

    if (step == "initialDiscover") {
        discoverZones()
    } else if (step == "resume") {
        if (state.knownSerials instanceof List && !state.knownSerials.isEmpty()) {
            refreshAllDeviceDetails()
        } else {
            discoverZones()
        }
    }
    if (!state.socketIoActive) {
        runIn(2, "maybeStartCredentialFetch", [overwrite: true])
    }
}

def maybeStartCredentialFetch() {
    if (state.cloudOffline) return
    if (!tokenIsFresh()) return
    startSocketIoPasswordFetch()
}

def failPendingAuthRequests() {
    def queue = (state.pendingHttpQueue instanceof List) ? (state.pendingHttpQueue as List) : []
    state.pendingHttpQueue = []
    def restoredSerials = [] as Set
    queue.each { pending ->
        if (pending?.ctx?.requestType == "command" && pending.ctx.serial) {
            def serial = pending.ctx.serial as String
            state.commandInFlight[serial] = false
            clearFailedCommandCache(serial)
            // Restore into pending + cache so local/cloud retries can rebuild payloads.
            if (!(state.commandPending[serial] instanceof Map)) state.commandPending[serial] = [:]
            if (pending.body?.commands instanceof Map) {
                pending.body.commands.each { k, v ->
                    state.commandPending[serial][k] = v
                    cacheCommand(serial, k as String, v)
                }
            }
            if (state.deviceData[serial]) pushStateToChildren(serial)
            restoredSerials << serial
        }
    }
    restoredSerials.each { serial -> processNextCommand(serial as String) }
    log.error "Authentication failed; deferred cloud requests were not completed"
}

def loginWithPassword() {
    def params = [
        uri: "${API_BASE}/${API_VERSION}/login",
        headers: baseHeaders(),
        body: JsonOutput.toJson([
            username: settings.username,
            password: settings.password,
            appVersion: API_APP_VERSION
        ]),
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: 30
    ]
    asynchttpPost("authCallback", params, [action: "login"])
}

def refreshAccessToken() {
    if (atomicState.refreshInProgress) {
        runIn(2, "refreshAccessToken", [overwrite: true])
        return
    }
    if (!atomicState.refreshToken) {
        loginWithPassword()
        return
    }
    atomicState.refreshInProgress = true
    def params = [
        uri: "${API_BASE}/${API_VERSION}/refresh",
        headers: baseHeaders(),
        body: JsonOutput.toJson([refresh: atomicState.refreshToken]),
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: 30
    ]
    asynchttpPost("authCallback", params, [action: "refresh"])
}

def authCallback(response, data) {
    def status = safeStatus(response)
    try {
        if (status == 200) {
            def json = parseAsyncBody(response)
            if (data?.action == "refresh") {
                atomicState.accessToken = json?.access
                atomicState.refreshToken = json?.refresh
            } else {
                atomicState.accessToken = json?.token?.access
                atomicState.refreshToken = json?.token?.refresh
            }
            if (!atomicState.accessToken) {
                throw new Exception("Auth response missing access token")
            }
            atomicState.tokenExpiresAt = now() + TOKEN_TTL_MS
            atomicState.refreshInProgress = false
            onAuthSuccess()
        } else {
            atomicState.refreshInProgress = false
            log.error "Auth ${data?.action} failed: HTTP ${status}"
            if (data?.action == "refresh") {
                loginWithPassword()
            } else if (data?.action == "login") {
                def offline = tryEnterOfflineMode("login failed")
                failPendingAuthRequests()
                if (offline) {
                    onOfflineBoot(state.authNextStep ?: "resume")
                }
            }
        }
    } catch (Exception e) {
        atomicState.refreshInProgress = false
        log.error "Auth callback error: ${e.message}"
        def offline = tryEnterOfflineMode("auth error")
        failPendingAuthRequests()
        if (offline) {
            onOfflineBoot(state.authNextStep ?: "resume")
        } else if (data?.action == "refresh") {
            loginWithPassword()
        }
    }
}

// --- HTTP layer ---

def queuePendingRequest(String method, String path, Map body, Map ctx) {
    if (!(state.pendingHttpQueue instanceof List)) state.pendingHttpQueue = []
    def queue = state.pendingHttpQueue as List
    // Keep only one pending command per serial; otherwise append.
    if (ctx?.requestType == "command" && ctx.serial) {
        queue.removeAll { it?.ctx?.requestType == "command" && it.ctx.serial == ctx.serial }
    }
    queue << [method: method, path: path, body: body, ctx: ctx]
    state.pendingHttpQueue = queue
}

def apiGet(String path, Map ctx) {
    if (!tokenIsFresh()) {
        queuePendingRequest("GET", path, null, ctx)
        if (atomicState.refreshToken) refreshAccessToken()
        else loginWithPassword()
        return
    }
    def params = [
        uri: "${API_BASE}/${API_VERSION}${path}",
        headers: authHeaders(atomicState.accessToken),
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: 30
    ]
    asynchttpGet("httpCallback", params, ctx + [method: "GET", path: path])
}

def apiPost(String path, Map body, Map ctx) {
    if (!tokenIsFresh()) {
        queuePendingRequest("POST", path, body, ctx)
        if (atomicState.refreshToken) refreshAccessToken()
        else loginWithPassword()
        return false
    }
    def params = [
        uri: "${API_BASE}/${API_VERSION}${path}",
        headers: authHeaders(atomicState.accessToken),
        body: JsonOutput.toJson(body),
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: 30
    ]
    asynchttpPost("httpCallback", params, ctx + [method: "POST", path: path, postBody: body])
    return true
}

def httpCallback(response, data) {
    if (!data) {
        log.error "httpCallback called with null data"
        return
    }
    def status = safeStatus(response)
    def retry = (data.retry ?: 0) as int

    if (status == 401 && !data.authRetried) {
        def replayCtx = [:]
        data.each { k, v ->
            if (!(k in ["method", "path", "postBody"])) replayCtx[k] = v
        }
        replayCtx.authRetried = true
        queuePendingRequest(data.method as String, data.path as String, data.postBody as Map, replayCtx)
        if (data.requestType == "command" && data.serial) {
            state.commandInFlight[data.serial] = false
        }
        if (atomicState.refreshToken) refreshAccessToken()
        else loginWithPassword()
        return
    }

    if ((status == 429 || status == 0 || status == 408) && retry < 3) {
        def headers = [:]
        try { headers = response?.getHeaders() ?: [:] } catch (ignored) {}
        def retryAfter = headers["Retry-After"] ?: headers["retry-after"]
        def delay = retryAfter ? Math.max(1, retryAfter as int) : ((60 * Math.pow(2, retry)) as int)
        log.warn "Retrying ${data.requestType} in ${delay}s (HTTP ${status})"
        if (data.requestType == "command" && data.serial) {
            state.commandInFlight[data.serial] = false
        }
        runIn(delay as Long, "retryHttp", [data: data + [retry: retry + 1], overwrite: false])
        return
    }

    try {
        def parsed = (status >= 200 && status < 300) ? parseAsyncBody(response) : null
        dispatchResponse(data.requestType as String, status, parsed, data)
    } catch (Exception e) {
        log.error "httpCallback error for ${data.requestType}: ${e.message}"
        if (data.requestType == "command" && data.serial) {
            state.commandInFlight[data.serial] = false
            clearFailedCommandCache(data.serial as String)
            processNextCommand(data.serial as String)
        } else {
            dispatchResponse(data.requestType as String, status, null, data)
        }
    }
}

def retryHttp(data) {
    if (!data) return
    if (data.method == "GET") {
        apiGet(data.path as String, data as Map)
    } else {
        apiPost(data.path as String, (data.postBody ?: [:]) as Map, data as Map)
    }
}

def dispatchResponse(String type, int status, parsed, Map data) {
    switch (type) {
        case "zones":
            handleZonesResponse(status, parsed)
            break
        case "device":
            handleDeviceResponse(status, parsed, data.serial as String)
            break
        case "profile":
            handleProfileResponse(status, parsed, data.serial as String)
            break
        case "status":
            handleStatusResponse(status, parsed, data.serial as String)
            break
        case "notifications":
            handleNotificationsResponse(status, parsed, data.zoneId as String)
            break
        case "wireless":
            handleWirelessResponse(status, parsed, data.serial as String)
            break
        case "command":
            handleCommandResponse(status, parsed, data.serial as String)
            break
        default:
            logDebug "Unhandled response type ${type}"
    }
}

// --- Polling schedules ---

def schedulePolling() {
    def interval = (settings.pollInterval ?: "60") as int
    def cronMin = interval <= 60 ? "0/1" : (interval <= 300 ? "0/5" : "0/10")
    schedule("0 ${cronMin} * * * ?", "pollDevices")
    schedule("0 0/15 * * * ?", "pollZones")
    schedule("0 0/5 * * * ?", "pollStatusAll")
    schedule("0 0/15 * * * ?", "pollNotificationsAll")
    schedule("0 0 3 * * ?", "pollProfilesAll")
    schedule("0 0/15 * * * ?", "cullCommandCache")
    schedule("0 0 4 * * ?", "dailyCredentialRefresh")
}

def dailyCredentialRefresh() {
    if (state.cloudOffline || !tokenIsFresh()) return
    startSocketIoPasswordFetch()
}

def pollDevices() {
    if (state.cloudOffline) {
        refreshAllDeviceDetailsLocal()
        return
    }
    refreshAllDeviceDetails()
}

def pollZones() {
    if (state.cloudOffline) return
    discoverZones()
}

def pollStatusAll() {
    if (state.cloudOffline) {
        (state.knownSerials ?: []).each { serial ->
            localPollAdapter(serial as String)
        }
        return
    }
    (state.knownSerials ?: []).each { serial ->
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
    }
}

def pollNotificationsAll() {
    if (state.cloudOffline) return
    (state.zoneIndex ?: [:]).each { serial, info ->
        if (info?.zoneId) {
            apiGet("/zones/${info.zoneId}/notification-preferences", [
                requestType: "notifications", zoneId: info.zoneId, serial: serial
            ])
        }
    }
}

def pollProfilesAll() {
    if (state.cloudOffline) {
        (state.knownSerials ?: []).each { serial ->
            localPollProfile(serial as String)
        }
        return
    }
    (state.knownSerials ?: []).each { serial ->
        apiGet("/devices/${serial}/profile", [requestType: "profile", serial: serial])
    }
}

def cullCommandCache() {
    def nowMs = now()
    def cache = state.commandCache ?: [:]
    cache.each { serial, fields ->
        if (!(fields instanceof Map)) return
        def expired = []
        fields.each { field, entry ->
            if (!entry?.timestamp || (nowMs - (entry.timestamp as long) >= COMMAND_TTL_MS)) {
                expired << field
            }
        }
        expired.each { field -> fields.remove(field) }
    }
}

// --- Discovery ---

def discoverZones() {
    if (!settings.siteId) {
        log.warn "discoverZones: siteId not set"
        return
    }
    apiGet("/sites/${settings.siteId}/zones", [requestType: "zones"])
}

def refreshAllDeviceDetails() {
    ensureAllZoneChildren()
    (state.knownSerials ?: []).each { serial ->
        refreshDeviceDetail(serial as String)
    }
}

def refreshAllDeviceDetailsLocal() {
    ensureAllZoneChildren()
    (state.knownSerials ?: []).eachWithIndex { serial, idx ->
        runIn(idx as Long, "refreshDeviceDetailLocalDelayed", [data: [serial: serial], overwrite: false])
    }
}

def refreshDeviceDetailLocalDelayed(data) {
    def serial = data?.serial as String
    if (serial) refreshDeviceDetailLocal(serial)
}

def refreshDeviceDetail(String serial) {
    if (!serial) return
    if (canUseLocal(serial)) {
        refreshDeviceDetailLocal(serial)
        return
    }
    apiGet("/devices/${serial}", [requestType: "device", serial: serial])
    if (state.zoneIndex[serial]?.hasSensor) {
        apiGet("/devices/${serial}/sensor", [requestType: "wireless", serial: serial])
    }
}

def refreshDeviceDetailLocal(String serial) {
    if (!serial || !canUseLocal(serial)) return
    // Status only; profile/adapter/sensors are chained after the response
    // so the adapter never sees overlapping connections.
    localPollStatus(serial)
}

def handleZonesResponse(int status, parsed) {
    if (status != 200 || !(parsed instanceof List)) {
        log.error "Zones fetch failed: HTTP ${status}"
        return
    }

    def activeSerials = [] as Set
    parsed.each { zone ->
        if (!zone?.adapter?.deviceSerial) return
        def serial = zone.adapter.deviceSerial as String
        def zoneId = zone.id as String
        def zoneName = (zone.name ?: "Zone ${zoneId}") as String
        def hasSensor = zone.adapter.hasSensor == true
        def hasMhk2 = zone.adapter.hasMhk2 == true

        activeSerials << serial
        state.zoneIndex[serial] = [
            zoneId: zoneId,
            zoneName: zoneName,
            hasSensor: hasSensor,
            hasMhk2: hasMhk2,
            humidity: zone.adapter.humidity
        ]
        if (zone.adapter.humidity != null) {
            def device = (state.deviceData[serial] instanceof Map) ? (state.deviceData[serial] as Map) : [:]
            device.humidity = zone.adapter.humidity
            state.deviceData[serial] = device
        }

        ensureZoneChildren(serial, zoneId, zoneName, hasSensor)
        if (state.deviceData[serial] instanceof Map) {
            pushIndoorState(serial)
        }
        refreshDeviceDetail(serial)
        apiGet("/devices/${serial}/profile", [requestType: "profile", serial: serial])
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
        apiGet("/zones/${zoneId}/notification-preferences", [
            requestType: "notifications", zoneId: zoneId, serial: serial
        ])
    }

    state.knownSerials = activeSerials as List
    markMissingSerialsStale(activeSerials)
    syncManualIpSettings()
    recomputeOfflineReady()
    if (!state.cloudOffline && tokenIsFresh()) {
        runIn(3, "maybeStartCredentialFetch", [overwrite: true])
    }
    if (settings.enableSubnetScan) {
        runIn(5, "startIpDiscovery", [overwrite: true])
    }
}

def ensureAllZoneChildren() {
    (state.zoneIndex ?: [:]).each { serial, info ->
        if (!info?.zoneId) return
        ensureZoneChildren(
            serial as String,
            info.zoneId as String,
            (info.zoneName ?: "Zone") as String,
            info.hasSensor == true
        )
    }
}

def ensureZoneChildren(String serial, String zoneId, String zoneName, Boolean hasSensor) {
    def tstat = ensureChild("mc-${serial}-thermostat", "Mitsubishi Comfort Thermostat", "${zoneName} Thermostat", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "thermostat"
    ])
    if (!tstat) return
    migrateLegacyAppChildren(serial, zoneId)
    try {
        tstat.ensureComponents([
            serial: serial,
            zoneId: zoneId,
            zoneName: zoneName,
            hasSensor: hasSensor
        ])
    } catch (Exception e) {
        log.error "Failed to ensure components for ${tailSerial(serial)}: ${e.message}"
    }
}

def migrateLegacyAppChildren(String serial, String zoneId) {
    [
        "mc-${serial}-indoor",
        "mc-${serial}-diag",
        "mc-${zoneId}-filter",
        "mc-${serial}-wireless"
    ].each { dni ->
        def child = getChildDevice(dni)
        if (!child) return
        log.info "Migrating: removing legacy app child ${child.label} (${dni})"
        try {
            deleteChildDevice(dni)
        } catch (Exception e) {
            log.error "Failed to remove legacy child ${dni}: ${e.message}"
        }
    }
}

def ensureChild(String dni, String driverName, String label, Map dataValues) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("ephrayim", driverName, dni, [label: label, name: label, isComponent: false])
            log.info "Created child ${label} (${dni})"
        } catch (Exception e) {
            log.error "Failed to create child ${dni}: ${e.message}"
            return null
        }
    } else if (child.label != label && !child.label) {
        child.setLabel(label)
    }
    dataValues.each { k, v ->
        if (v != null) child.updateDataValue(k as String, v.toString())
    }
    return child
}

def markMissingSerialsStale(Set activeSerials) {
    getChildDevices()?.each { tstat ->
        if (tstat.getDataValue("deviceType") && tstat.getDataValue("deviceType") != "thermostat") return
        def serial = tstat.getDataValue("deviceSerial")
        if (serial && !(serial in activeSerials)) {
            try {
                tstat.pushCloudStatus("stale")
            } catch (Exception e) {
                tstat.sendEvent(name: "cloudStatus", value: "stale")
                log.error "Failed to mark components stale for ${tailSerial(serial)}: ${e.message}"
            }
        }
    }
}

def getStaleChildren() {
    return getChildDevices()?.findAll {
        it.getDataValue("deviceType") == "thermostat" && it.currentValue("cloudStatus") == "stale"
    } ?: []
}

// --- Response handlers ---

def handleDeviceResponse(int status, parsed, String serial) {
    if (!serial) return
    if (status == 200 && parsed instanceof Map) {
        applyCommandCacheToDevice(serial, parsed)
        state.deviceData[serial] = parsed
        state.failCounts[serial] = 0
        pushStateToChildren(serial)
    } else {
        def fails = ((state.failCounts[serial] ?: 0) as int) + 1
        state.failCounts[serial] = fails
        def cloudStatus = resolveCloudStatus(serial)
        if (cloudStatus != "online") {
            pushCloudStatus(serial, cloudStatus)
        }
        log.warn "Device ${tailSerial(serial)} fetch failed: HTTP ${status} (fail ${fails})"
    }
}

def handleProfileResponse(int status, parsed, String serial) {
    if (status == 200 && parsed && serial) {
        state.profiles[serial] = parsed
        pushThermostatState(serial)
    }
}

def handleStatusResponse(int status, parsed, String serial) {
    if (status == 200 && parsed instanceof Map && serial) {
        state.statusData[serial] = parsed
        mergeStatusIntoLocalCreds(serial, parsed)
        pushDiagnosticState(serial)
    }
}

def handleNotificationsResponse(int status, parsed, String zoneId) {
    if (status == 200 && parsed instanceof Map && zoneId) {
        state.notificationData[zoneId] = parsed
        def entry = (state.zoneIndex ?: [:]).find { k, v -> v?.zoneId == zoneId }
        if (entry) pushFilterState(entry.key as String)
    }
}

def handleWirelessResponse(int status, parsed, String serial) {
    if (status == 200 && parsed instanceof Map && serial) {
        state.wirelessData[serial] = parsed
        pushWirelessState(serial)
    }
}

def handleCommandResponse(int status, parsed, String serial) {
    if (!serial) return
    state.commandInFlight[serial] = false
    if (status == 200) {
        state.lastCommandSend[serial] = now()
        state.lastCommandFields.remove(serial)
        logDebug "Command accepted for ${tailSerial(serial)}"
        runIn(1, "fetchDeviceDetail", [data: [serial: serial], overwrite: false])
    } else {
        log.error "Command failed for ${tailSerial(serial)}: HTTP ${status}"
        clearFailedCommandCache(serial)
        // Revert optimistic UI to last known cloud values.
        if (state.deviceData[serial]) {
            pushStateToChildren(serial)
        }
        fetchDeviceDetail([serial: serial])
    }
    processNextCommand(serial)
}

def fetchDeviceDetail(data) {
    def serial = data?.serial
    if (serial) refreshDeviceDetail(serial as String)
}

// --- State push ---

def pushStateToChildren(String serial) {
    pushThermostatState(serial)
    pushIndoorState(serial)
    pushDiagnosticState(serial)
    pushFilterState(serial)
    if (state.zoneIndex[serial]?.hasSensor) {
        pushWirelessState(serial)
    }
}

def pushCloudStatus(String serial, String cloudStatus) {
    def tstat = getThermostat(serial)
    if (!tstat) return
    try {
        tstat.applyThermostatState([componentCloudStatus: cloudStatus])
    } catch (Exception e) {
        tstat.sendEvent(name: "cloudStatus", value: cloudStatus)
        log.error "pushCloudStatus failed for ${tailSerial(serial)}: ${e.message}"
    }
}

def resolveCloudStatus(String serial) {
    def fails = ((state.failCounts[serial] ?: 0) as int)
    def path = state.lastConnectionPath[serial] as String
    def talkingLocal = path == "local" || path == "offline"
    if (talkingLocal && fails < STALE_FAIL_THRESHOLD) {
        return "online"
    }
    if (fails >= STALE_FAIL_THRESHOLD) {
        return "stale"
    }
    def device = state.deviceData[serial]
    if (device instanceof Map && device.connected == false) {
        return "offline"
    }
    return "online"
}

def roundHumidity(value) {
    if (value == null) return null
    return Math.round((value as double) * 10) / 10.0
}

def getThermostat(String serial) {
    return getChildDevice("mc-${serial}-thermostat")
}

def pushThermostatState(String serial) {
    def child = getThermostat(serial)
    if (!child) return
    def device = state.deviceData[serial]
    if (!(device instanceof Map)) return

    def profile = firstProfile(serial)
    def hubMode = mapKumoMode(device)
    def tempUnit = useFahrenheit() ? "°F" : "°C"
    def room = cToDisplay(device.roomTemp)
    def heatSp = cToDisplay(device.spHeat)
    def coolSp = cToDisplay(device.spCool)
    def fan = API_TO_UI_FAN[device.fanSpeed as String] ?: device.fanSpeed
    def vane = API_TO_UI_VANE[device.airDirection as String] ?: device.airDirection
    def cloudStatus = resolveCloudStatus(serial)

    def supportedModes = buildSupportedModes(profile)
    def supportedFans = JsonOutput.toJson(buildSupportedFanModes(profile))
    def setpointLimits = buildSetpointLimits(profile)

    def singleSp = null
    if (hubMode == "heat") singleSp = heatSp
    else if (hubMode == "cool") singleSp = coolSp
    else if (hubMode == "auto") singleSp = coolSp ?: heatSp

    def connPath = state.lastConnectionPath[serial] ?: (state.cloudOffline ? "offline" : "cloud")

    try {
        def stateMap = [
            supportedModes: JsonOutput.toJson(supportedModes),
            supportedFanModes: supportedFans,
            tempUnit: tempUnit,
            temperature: room,
            heatingSetpoint: heatSp,
            coolingSetpoint: coolSp,
            thermostatSetpoint: singleSp,
            thermostatMode: hubMode,
            thermostatFanMode: fan,
            thermostatOperatingState: computeOperatingState(hubMode, room, heatSp, coolSp, device),
            vanePosition: vane,
            cloudStatus: cloudStatus,
            connectionPath: connPath,
            model: device.model?.materialDescription,
            serialNumber: device.serialNumber
        ]
        stateMap.putAll(setpointLimits)
        def display = (device.displayConfig instanceof Map) ? device.displayConfig : [:]
        def defrost = device.defrost != null ? device.defrost : display.defrost
        def standby = device.standby != null ? device.standby : display.standby
        if (defrost != null) stateMap.defrost = defrost
        if (standby != null) stateMap.standby = standby
        attachComponentJson(stateMap, serial)
        logDebug "applyThermostatState ${tailSerial(serial)} keys=${stateMap.keySet()}"
        child.applyThermostatState(stateMap)
    } catch (Exception e) {
        log.error "pushThermostatState failed for ${tailSerial(serial)}: ${e.message}"
    }
}

def indoorStateMap(String serial) {
    def device = state.deviceData[serial]
    if (!(device instanceof Map)) return null
    def humidity = device.humidity != null ? device.humidity : state.zoneIndex[serial]?.humidity
    def map = [
        temperature: cToDisplay(device.roomTemp),
        tempUnit: useFahrenheit() ? "°F" : "°C",
        cloudStatus: resolveCloudStatus(serial)
    ]
    if (humidity != null) map.humidity = roundHumidity(humidity)
    if (device.tempSource != null) map.tempSource = device.tempSource.toString()
    if (device.activeThermistor != null) map.activeThermistor = device.activeThermistor.toString()
    return map
}

def diagnosticStateMap(String serial) {
    def status = state.statusData[serial]
    if (!(status instanceof Map)) return null
    return [
        rssi: status.routerRssi,
        firmwareVersion: status.firmwareVersion,
        routerSsid: status.routerSsid,
        cloudStatus: resolveCloudStatus(serial)
    ]
}

def filterStateMap(String serial) {
    def zoneId = state.zoneIndex[serial]?.zoneId
    if (!zoneId) return null
    def notifications = state.notificationData[zoneId]
    def device = state.deviceData[serial]
    def display = (device instanceof Map && device.displayConfig instanceof Map) ? device.displayConfig : [:]
    def filterDirty = (device instanceof Map && device.filterDirty != null) ? device.filterDirty : display.filter
    if (!(notifications instanceof Map) && filterDirty == null) return null
    def map = [cloudStatus: resolveCloudStatus(serial)]
    if (filterDirty != null) map.filterDirty = filterDirty
    if (notifications instanceof Map) {
        map.lastFilterReminder = notifications.filterDirtyReminderLastSent
        map.reminderIntervalDays = notifications.filterDirtyReminderInterval
        map.remindersEnabled = notifications.filterDirty != null ? notifications.filterDirty.toString() : null
    }
    return map
}

def wirelessStateMap(String serial) {
    def sensor = state.wirelessData[serial]
    if (!(sensor instanceof Map)) return null
    def tempC = sensor.temperature
    def temp = null
    if (tempC != null) {
        temp = useFahrenheit() ?
            Math.round((((tempC as double) * 9.0 / 5.0) + 32.0) * 10.0) / 10.0 :
            Math.round((tempC as double) * 10.0) / 10.0
    }
    return [
        temperature: temp,
        tempUnit: useFahrenheit() ? "°F" : "°C",
        humidity: roundHumidity(sensor.humidity),
        battery: sensor.battery,
        rssi: sensor.rssi,
        cloudStatus: resolveCloudStatus(serial)
    ]
}

def attachComponentJson(Map stateMap, String serial) {
    def tstat = getThermostat(serial)
    [
        indoor: indoorStateMap(serial),
        diag: diagnosticStateMap(serial),
        filter: filterStateMap(serial),
        wireless: wirelessStateMap(serial)
    ].each { type, payload ->
        if (!(payload instanceof Map)) return
        def json = JsonOutput.toJson(payload)
        stateMap["${type}Json"] = json
        try {
            tstat?.updateDataValue("comp_${type}_json", json)
        } catch (Exception e) {
            logDebug "updateDataValue comp_${type}_json failed: ${e.message}"
        }
    }
}

def pushComponentToThermostat(String serial, String key, Map payload) {
    def child = getThermostat(serial)
    if (!child || !(payload instanceof Map)) return
    def json = JsonOutput.toJson(payload)
    def extra = [:]
    extra["${key}Json"] = json
    try {
        child.updateDataValue("comp_${key}_json", json)
    } catch (Exception ignored) {}
    try {
        logDebug "push ${key}Json for ${tailSerial(serial)}: ${json}"
        child.applyThermostatState(extra)
    } catch (Exception e) {
        log.error "push ${key} state failed: ${e.message}"
    }
}

def pushIndoorState(String serial) {
    def map = indoorStateMap(serial)
    if (map) pushComponentToThermostat(serial, "indoor", map)
}

def pushWirelessState(String serial) {
    def map = wirelessStateMap(serial)
    if (map) pushComponentToThermostat(serial, "wireless", map)
}

def pushDiagnosticState(String serial) {
    def map = diagnosticStateMap(serial)
    if (map) pushComponentToThermostat(serial, "diag", map)
}

def pushFilterState(String serial) {
    def map = filterStateMap(serial)
    if (map) pushComponentToThermostat(serial, "filter", map)
}

def normalizeProfile(profile) {
    if (!profile) return null
    if (profile instanceof List) {
        if (profile.isEmpty()) return null
        profile = profile[0]
    }
    return (profile instanceof Map) ? profile : null
}

def buildSupportedModes(profile) {
    def modes = ["off"]
    def p = normalizeProfile(profile)
    if (!p) return modes + ["heat", "cool", "auto"]
    if (p.hasModeHeat || p.maximumSetPoints?.heat != null) modes << "heat"
    if (p.hasModeCool || p.maximumSetPoints?.cool != null) modes << "cool"
    if (p.hasModeDry) modes << "dry"
    if (p.hasModeFan || p.hasModeVent) modes << "fan"
    if (p.hasModeAuto || p.maximumSetPoints?.auto != null) modes << "auto"
    return modes
}

def buildSupportedFanModes(profile) {
    def p = normalizeProfile(profile)
    if (!p) return UI_FAN_ORDER

    def count = (p.numberOfFanSpeeds ?: 0) as int
    def hasAuto = p.hasFanSpeedAuto ? true : false

    if (count <= 0) {
        return hasAuto ? ["auto"] : []
    }

    def manual = FAN_SPEEDS_BY_COUNT[count] ?: FAN_SPEEDS_BY_COUNT[5]
    def fans = []
    if (hasAuto) fans << "auto"
    fans.addAll(manual)
    return fans
}

def buildSetpointLimits(profile) {
    def p = normalizeProfile(profile)
    if (!p) return [:]

    def minSp = (p.minimumSetPoints instanceof Map) ? p.minimumSetPoints : [:]
    def maxSp = (p.maximumSetPoints instanceof Map) ? p.maximumSetPoints : [:]
    def limits = [:]

    if (minSp.heat != null) limits.minHeatingSetpoint = cToDisplay(minSp.heat)
    if (maxSp.heat != null) limits.maxHeatingSetpoint = cToDisplay(maxSp.heat)
    if (minSp.cool != null) limits.minCoolingSetpoint = cToDisplay(minSp.cool)
    if (maxSp.cool != null) limits.maxCoolingSetpoint = cToDisplay(maxSp.cool)
    if (minSp.auto != null) limits.minAutoSetpoint = cToDisplay(minSp.auto)
    if (maxSp.auto != null) limits.maxAutoSetpoint = cToDisplay(maxSp.auto)
    if (p.hasModeDry) {
        if (minSp.dry != null) limits.minDrySetpoint = cToDisplay(minSp.dry)
        else if (minSp.cool != null) limits.minDrySetpoint = cToDisplay(minSp.cool)
        if (maxSp.dry != null) limits.maxDrySetpoint = cToDisplay(maxSp.dry)
        else if (maxSp.cool != null) limits.maxDrySetpoint = cToDisplay(maxSp.cool)
    }
    return limits
}

def mapKumoMode(device) {
    if (!(device instanceof Map)) return "off"
    if ((device.power ?: 0) as int == 0 || device.operationMode == "off") return "off"
    return KUMO_TO_HUB_MODE[device.operationMode as String] ?: "off"
}

def computeOperatingState(String hubMode, room, heatSp, coolSp, device) {
    if (hubMode == "off") return "idle"
    if (hubMode == "heat") return "heating"
    if (hubMode == "cool") return "cooling"
    if (hubMode == "dry") return "cooling"
    if (hubMode == "fan") return "fan only"
    if (hubMode != "auto") return "idle"

    def deadBand = useFahrenheit() ? 1.0d : 0.5d
    def op = device?.operationMode
    if (room == null) return "idle"

    def roomN = room as double
    if (op == "autoCool" && coolSp != null && roomN > ((coolSp as double) + deadBand)) return "cooling"
    if (op == "autoHeat" && heatSp != null && roomN < ((heatSp as double) - deadBand)) return "heating"
    if (coolSp != null && roomN > ((coolSp as double) + deadBand)) return "cooling"
    if (heatSp != null && roomN < ((heatSp as double) - deadBand)) return "heating"
    return "idle"
}

def firstProfile(String serial) {
    return normalizeProfile(state.profiles[serial])
}

// --- Temperature conversion ---

def useFahrenheit() {
    return location?.temperatureScale != "C"
}

def cToDisplay(celsius) {
    if (celsius == null) return null
    def c = roundHalf(celsius)
    if (!useFahrenheit()) return c
    if (C_TO_F.containsKey(c)) return C_TO_F[c]
    def cDouble = (c as BigDecimal).doubleValue()
    if (C_TO_F.containsKey(cDouble)) return C_TO_F[cDouble]
    return Math.round((c as double) * 9.0d / 5.0d + 32.0d)
}

def displayToC(temp) {
    if (temp == null) return null
    def t = temp as BigDecimal
    if (!useFahrenheit()) return roundHalf(t)
    def f = Math.round(t as double) as int
    if (F_TO_C.containsKey(f)) return F_TO_C[f]
    return roundHalf((((t as double) - 32.0d) * 5.0d) / 9.0d)
}

def roundHalf(val) {
    if (val == null) return null
    return Math.round((val as double) * 2.0d) / 2.0d
}

def clampSetpoint(String serial, celsius, String mode) {
    if (celsius == null) return null
    def profile = firstProfile(serial)
    def minC = 16.0d
    def maxC = 30.0d
    if (profile?.minimumSetPoints instanceof Map) {
        minC = (profile.minimumSetPoints[mode] ?: profile.minimumSetPoints.heat ?: minC) as double
    }
    if (profile?.maximumSetPoints instanceof Map) {
        maxC = (profile.maximumSetPoints[mode] ?: profile.maximumSetPoints.cool ?: maxC) as double
    }
    def clamped = Math.max(minC, Math.min(maxC, celsius as double))
    return roundHalf(clamped)
}

// --- Command cache & queue ---

def applyCommandCacheToDevice(String serial, Map device) {
    def cache = state.commandCache[serial]
    if (!(cache instanceof Map)) return
    def nowMs = now()
    cache.each { field, entry ->
        if (entry?.timestamp && (nowMs - (entry.timestamp as long) < COMMAND_TTL_MS)) {
            device[field] = entry.value
        }
    }
}

def cacheCommand(String serial, String field, value) {
    if (!(state.commandCache[serial] instanceof Map)) state.commandCache[serial] = [:]
    state.commandCache[serial][field] = [value: value, timestamp: now()]
    if (state.deviceData[serial] instanceof Map) {
        state.deviceData[serial][field] = value
    }
}

def clearFailedCommandCache(String serial) {
    def lastSent = state.lastCommandFields[serial]
    if (lastSent instanceof List) {
        lastSent.each { field ->
            state.commandCache[serial]?.remove(field)
        }
    }
    state.lastCommandFields.remove(serial)
}

def enqueueCommands(String serial, Map commands) {
    if (!serial || !commands) return
    if (!(state.commandPending[serial] instanceof Map)) state.commandPending[serial] = [:]
    commands.each { k, v ->
        state.commandPending[serial][k] = v
        cacheCommand(serial, k as String, v)
    }
    pushStateToChildren(serial)
    scheduleCommandWorker(serial)
}

def scheduleCommandWorker(String serial) {
    runIn(1, "processCommandQueue", [data: [serial: serial], overwrite: false])
}

def processCommandQueue(data) {
    def serial = data?.serial as String
    if (!serial) return

    if (state.commandInFlight[serial] || state.localInFlight[serial]) {
        runIn(2, "processCommandQueue", [data: [serial: serial], overwrite: false])
        return
    }

    def pending = state.commandPending[serial]
    if (!(pending instanceof Map) || pending.isEmpty()) return

    if (canUseLocal(serial)) {
        def toSend = [:]
        toSend.putAll(pending)
        state.commandPending[serial] = [:]
        state.lastCommandFields[serial] = toSend.keySet() as List
        state.commandInFlight[serial] = true
        sendLocalCommands(serial, toSend)
        return
    }

    if (state.cloudOffline) {
        log.warn "Cannot send command for ${tailSerial(serial)}: offline and local credentials incomplete; will retry"
        def waitSec = Math.max(30L, (COMMAND_GAP_MS / 1000L) as long)
        runIn(waitSec, "processCommandQueue", [data: [serial: serial], overwrite: false])
        return
    }

    def last = (state.lastCommandSend[serial] ?: 0L) as long
    def elapsed = now() - last
    if (elapsed < COMMAND_GAP_MS) {
        def waitSec = Math.max(1L, ((COMMAND_GAP_MS - elapsed) / 1000L) as long)
        runIn(waitSec, "processCommandQueue", [data: [serial: serial], overwrite: false])
        return
    }

    def toSend = [:]
    toSend.putAll(pending)
    state.commandPending[serial] = [:]
    state.lastCommandFields[serial] = toSend.keySet() as List
    state.commandInFlight[serial] = true

    def body = [deviceSerial: serial, commands: toSend]
    def sent = apiPost("/devices/send-command", body, [
        requestType: "command", serial: serial, postBody: body
    ])
    if (sent == false) {
        state.commandInFlight[serial] = false
    }
}

def processNextCommand(String serial) {
    if (state.commandPending[serial] instanceof Map && !state.commandPending[serial].isEmpty()) {
        scheduleCommandWorker(serial)
    }
}

def buildCommandBase(String serial) {
    def device = (state.deviceData[serial] instanceof Map) ? state.deviceData[serial] : [:]
    def cmds = [:]
    if (device.spCool != null) cmds.spCool = device.spCool
    if (device.spHeat != null) cmds.spHeat = device.spHeat
    return cmds
}

// --- Component API (called by child drivers) ---

def componentRefresh(child) {
    def serial = child?.getDataValue("deviceSerial")
    if (!serial) return
    if (state.cloudOffline || canUseLocal(serial)) {
        refreshDeviceDetailLocal(serial)
        localPollAdapter(serial)
    } else {
        refreshDeviceDetail(serial)
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
    }
    def zoneId = child.getDataValue("zoneId")
    if (zoneId && !state.cloudOffline) {
        apiGet("/zones/${zoneId}/notification-preferences", [
            requestType: "notifications", zoneId: zoneId, serial: serial
        ])
    }
}

def componentSetMode(child, String mode) {
    def serial = child?.getDataValue("deviceSerial")
    if (!serial || !mode) return
    def normalized = mode.toLowerCase()
    def cmds = buildCommandBase(serial)
    def kumo = HUB_TO_KUMO_MODE[normalized]
    if (!kumo) {
        log.warn "Unsupported thermostat mode: ${mode}"
        return
    }
    cmds.operationMode = kumo
    enqueueCommands(serial, cmds)
}

def componentSetHeatingSetpoint(child, temperature) {
    def serial = child?.getDataValue("deviceSerial")
    if (!serial || temperature == null) return
    def cmds = buildCommandBase(serial)
    def celsius = displayToC(temperature)
    cmds.spHeat = clampSetpoint(serial, celsius, "heat")
    // Keep current mode; if unit is off, switch to heat so the setpoint is meaningful.
    def current = mapKumoMode(state.deviceData[serial])
    if (current == "off") {
        cmds.operationMode = "heat"
    }
    enqueueCommands(serial, cmds)
}

def componentSetCoolingSetpoint(child, temperature) {
    def serial = child?.getDataValue("deviceSerial")
    if (!serial || temperature == null) return
    def cmds = buildCommandBase(serial)
    def celsius = displayToC(temperature)
    cmds.spCool = clampSetpoint(serial, celsius, "cool")
    def current = mapKumoMode(state.deviceData[serial])
    if (current == "off") {
        cmds.operationMode = "cool"
    }
    enqueueCommands(serial, cmds)
}

def componentSetFanSpeed(child, String fan) {
    def serial = child?.getDataValue("deviceSerial")
    if (!serial || !fan) return
    def normalized = fan.toLowerCase()
    def supported = buildSupportedFanModes(firstProfile(serial))
    if (!supported.contains(normalized)) {
        log.warn "Fan mode '${fan}' not supported for ${tailSerial(serial)}; supported: ${supported}"
        return
    }
    def apiFan = UI_TO_API_FAN[normalized] ?: normalized
    enqueueCommands(serial, [fanSpeed: apiFan])
}

def componentSetVanePosition(child, String vane) {
    def serial = child?.getDataValue("deviceSerial")
    if (!serial || !vane) return
    def apiVane = UI_TO_API_VANE[vane.toLowerCase()] ?: vane
    enqueueCommands(serial, [airDirection: apiVane])
}

// --- Utilities ---

def baseHeaders() {
    return [
        "x-app-version": API_APP_VERSION,
        "Content-Type": "application/json",
        "Accept": "application/json"
    ]
}

def authHeaders(String token) {
    def h = baseHeaders()
    h["Authorization"] = "Bearer ${token}"
    return h
}

def safeStatus(response) {
    try {
        if (response == null) return 0
        if (response.respondsTo("getStatus")) return response.getStatus() as int
        if (response.hasProperty("status")) return response.status as int
    } catch (ignored) {}
    return 0
}

def parseMaybeJson(raw) {
    if (raw == null) return null
    if (raw instanceof Map || raw instanceof List) return raw
    def text = raw as String
    if (!text?.trim()) return null
    return new JsonSlurper().parseText(text)
}

def parseAsyncBody(response) {
    try {
        if (response == null) return null
        if (response.respondsTo("getJson")) {
            def json = response.getJson()
            if (json != null) return json
        }
        if (response.hasProperty("json") && response.json != null) return response.json
        def text = null
        if (response.respondsTo("getData")) text = response.getData()
        else if (response.hasProperty("data")) text = response.data
        if (!text && response.respondsTo("getErrorData")) text = response.getErrorData()
        return parseMaybeJson(text)
    } catch (Exception e) {
        logDebug "parseAsyncBody failed: ${e.message}"
        return null
    }
}

def tailSerial(String serial) {
    if (!serial) return "?"
    if (serial.length() <= 6) return serial
    return serial.substring(serial.length() - 6)
}

// --- Local API: credentials, auth, HTTP, offline ---

def unitIpSettingKey(String serial) {
    return "unitIp_" + (serial ?: "").replaceAll("[^a-zA-Z0-9]", "_")
}

def syncManualIpSettings() {
    if (!(state.zoneIndex instanceof Map)) return
    state.zoneIndex.each { serial, info ->
        def s = serial as String
        def key = unitIpSettingKey(s)
        def ip = settings[key]?.toString()?.trim()
        if (!ip) return
        ensureLocalCredEntry(s)
        state.localCreds[s].address = ip
        state.localCreds[s].addressLocked = true
    }
    recomputeOfflineReady()
}

def ensureLocalCredEntry(String serial) {
    if (!(state.localCreds[serial] instanceof Map)) {
        state.localCreds[serial] = [:]
    }
}

def mergeStatusIntoLocalCreds(String serial, Map status) {
    if (!serial || !(status instanceof Map)) return
    ensureLocalCredEntry(serial)
    def creds = state.localCreds[serial] as Map
    if (status.cryptoSerial) creds.cryptoSerial = status.cryptoSerial as String
    if (status.mac) creds.mac = status.mac as String
    recomputeOfflineReady()
}

def recomputeOfflineReady() {
    def serials = state.knownSerials ?: []
    if (!serials) {
        state.offlineReady = false
        return
    }
    def ready = true
    serials.each { serial ->
        def s = serial as String
        def creds = state.localCreds[s]
        if (!(creds instanceof Map) || !creds.password || !creds.cryptoSerial || !creds.address) {
            ready = false
        }
    }
    state.offlineReady = ready
}

def offlineOperationAllowed() {
    return settings.allowOffline != false && (state.offlineReady || hasAnyLocalSerial())
}

def hasAnyLocalSerial() {
    return (state.knownSerials ?: []).any { serial ->
        canUseLocal(serial as String, true)
    }
}

def tryEnterOfflineMode(String reason) {
    if (!offlineOperationAllowed()) return false
    log.warn "Entering offline local mode: ${reason}"
    state.cloudOffline = true
    return true
}

def scheduleOfflineCloudProbe() {
    if (!offlineOperationAllowed()) return
    runIn((OFFLINE_PROBE_MS / 1000L) as Long, "offlineCloudProbe", [overwrite: true])
}

def offlineCloudProbe() {
    if (!offlineOperationAllowed()) return
    if (!state.cloudOffline) {
        scheduleOfflineCloudProbe()
        return
    }
    if (tokenIsFresh()) {
        state.cloudOffline = false
        state.lastCloudContact = new Date().format("yyyy-MM-dd HH:mm:ss")
        discoverZones()
        return
    }
    loginWithPassword()
    scheduleOfflineCloudProbe()
}

def canUseLocal(String serial, Boolean ignoreDegrade = false) {
    if (!serial) return false
    if (state.cloudOffline && offlineOperationAllowed()) {
        return localCredsComplete(serial)
    }
    if (settings.preferLocal == false) return false
    if (!ignoreDegrade && localDegraded(serial)) return false
    return localCredsComplete(serial)
}

def localCredsComplete(String serial) {
    def creds = state.localCreds[serial]
    return creds instanceof Map && creds.password && creds.cryptoSerial && creds.address
}

def localDegraded(String serial) {
    def until = (state.localDegradedUntil[serial] ?: 0L) as long
    return until > now()
}

def recordLocalSuccess(String serial) {
    state.localFailCounts[serial] = 0
    state.lastConnectionPath[serial] = state.cloudOffline ? "offline" : "local"
}

def recordLocalFailure(String serial) {
    def fails = ((state.localFailCounts[serial] ?: 0) as int) + 1
    state.localFailCounts[serial] = fails
    if (!state.cloudOffline && fails >= LOCAL_FAIL_THRESHOLD) {
        state.localDegradedUntil[serial] = now() + LOCAL_DEGRADE_MS
        def creds = state.localCreds[serial]
        if (creds instanceof Map && !creds.addressLocked) {
            creds.remove("address")
        }
    }
    if (state.cloudOffline) {
        pushCloudStatus(serial, "offline")
    }
}

def hexToBytes(String hex) {
    if (!hex) return new byte[0]
    return HexUtils.hexStringToByteArray(hex.trim())
}

def bytesToHex(byte[] bytes) {
    return HexUtils.byteArrayToHexString(bytes).toLowerCase()
}

def sha256Bytes(byte[] data) {
    return MessageDigest.getInstance("SHA-256").digest(data)
}

def sha256Concat(byte[] a, byte[] b) {
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    md.update(a)
    md.update(b)
    return md.digest()
}

def computeKumoToken(String passwordB64, String cryptoSerialHex, String bodyStr) {
    try {
        byte[] password = Base64.decodeBase64(passwordB64)
        byte[] postData = bodyStr.getBytes("UTF-8")
        byte[] dataHash = sha256Concat(password, postData)
        String serialHex = cryptoSerialHex.trim().toLowerCase()
        if (serialHex.length() < CRYPTO_SERIAL_MIN_BYTES * 2) return null

        // Build the 88-byte intermediate buffer as hex (avoids System.arraycopy in sandbox).
        StringBuilder sb = new StringBuilder(176)
        sb.append(W_PARAM_HEX)
        sb.append(bytesToHex(dataHash))
        sb.append("084000")
        for (int i = 0; i < 12; i++) sb.append("00")
        sb.append(serialHex.substring(16, 18))
        sb.append(serialHex.substring(8, 16))
        sb.append(serialHex.substring(0, 8))

        return bytesToHex(sha256Bytes(hexToBytes(sb.toString())))
    } catch (Exception e) {
        log.error "computeKumoToken failed: ${e.message}"
        return null
    }
}

def localPut(String serial, String bodyStr, Map ctx) {
    def creds = state.localCreds[serial]
    if (!(creds instanceof Map)) return false
    if (state.localInFlight[serial]) return false
    def token = computeKumoToken(creds.password as String, creds.cryptoSerial as String, bodyStr)
    if (!token) return false
    def ip = (ctx?.overrideIp ?: creds.address) as String
    if (!ip) return false
    state.localInFlight[serial] = true
    def params = [
        uri: "http://${ip}/api?m=${token}",
        headers: [
            "Accept": "application/json, text/plain, */*",
            "Content-Type": "application/json"
        ],
        body: bodyStr,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: LOCAL_HTTP_TIMEOUT
    ]
    asynchttpPut("localHttpCallback", params, ctx + [serial: serial, bodyStr: bodyStr, retry: ctx?.retry ?: 0])
    return true
}

def localHttpCallback(response, data) {
    def serial = data?.serial as String
    if (!serial) return
    def status = safeStatus(response)
    def parsed = (status >= 200 && status < 300) ? parseAsyncBody(response) : null
    def ok = parsed instanceof Map && parsed.r != null

    if (!ok && (data.retry as int) < 1 && (status == 0 || status == 408)) {
        state.localInFlight[serial] = false
        runIn(1, "localPutRetry", [data: data + [retry: 1], overwrite: false])
        return
    }

    state.localInFlight[serial] = false
    state.ipProbeActive?.remove(serial)
    if (ok) {
        recordLocalSuccess(serial)
        dispatchLocalResponse(data.requestType as String, parsed, data)
    } else {
        if (data.requestType != "localProbe") {
            recordLocalFailure(serial)
        }
        dispatchLocalResponse(data.requestType as String, null, data)
    }
}

def localPutRetry(data) {
    def serial = data?.serial as String
    if (!serial) return
    localPut(serial, data.bodyStr as String, data as Map)
}

def dispatchLocalResponse(String type, parsed, Map data) {
    def serial = data?.serial as String
    if (!serial) return
    switch (type) {
        case "localWireless":
            if (parsed) applyLocalWirelessResponse(serial, parsed)
            scheduleNextLocalPoll(serial, "wireless")
            break
        case "localMhk2":
            if (parsed) applyLocalMhk2Response(serial, parsed)
            runIn(1, "localPollProfileDelayed", [data: [serial: serial], overwrite: false])
            break
        case "localCommand":
            handleLocalCommandResponse(serial, parsed != null)
            break
        case "localProbe":
            if (parsed) {
                ensureLocalCredEntry(serial)
                state.localCreds[serial].address = data.probeIp as String
                recomputeOfflineReady()
                log.info "Matched ${tailSerial(serial)} to IP ${data.probeIp}"
                state.ipProbeSerialIdx = ((state.ipProbeSerialIdx ?: 0) as int) + 1
                state.ipProbeIpIdx = 0
            }
            break
        case "localProfile":
            if (parsed) applyLocalProfileResponse(serial, parsed)
            else runIn(1, "localPollAdapterDelayed", [data: [serial: serial], overwrite: false])
            break
        case "localAdapter":
            if (parsed) applyLocalAdapterResponse(serial, parsed)
            break
        case "localStatus":
            if (parsed) applyLocalStatusResponse(serial, parsed)
            else handleLocalPollFailure(serial)
            break
        default:
            logDebug "Unhandled local response ${type}"
    }
}

def applyLocalStatusResponse(String serial, Map parsed) {
    def raw = parsed?.r?.indoorUnit?.status
    if (!(raw instanceof Map)) {
        handleLocalPollFailure(serial)
        return
    }
    def device = (state.deviceData[serial] instanceof Map) ? (state.deviceData[serial] as Map) : [:]
    applyCommandCacheToDevice(serial, device)
    device.operationMode = raw.mode
    device.airDirection = raw.vaneDir
    device.fanSpeed = raw.fanSpeed
    device.spHeat = raw.spHeat
    device.spCool = raw.spCool
    device.roomTemp = raw.roomTemp
    device.connected = true
    if (raw.humidity != null) device.humidity = raw.humidity
    if (raw.filterDirty != null) device.filterDirty = raw.filterDirty
    if (raw.defrost != null) device.defrost = raw.defrost
    if (raw.standby != null) device.standby = raw.standby
    if (raw.tempSource != null) device.tempSource = raw.tempSource
    if (raw.activeThermistor != null) device.activeThermistor = raw.activeThermistor
    state.deviceData[serial] = device
    state.failCounts[serial] = 0
    pushStateToChildren(serial)
    scheduleNextLocalPoll(serial, "status")
}

def localPollWirelessDelayed(data) {
    def serial = data?.serial as String
    if (serial) localPollWireless(serial)
}

def localPollMhk2Delayed(data) {
    def serial = data?.serial as String
    if (serial) localPollMhk2(serial)
}

def scheduleNextLocalPoll(String serial, String after) {
    if (!serial) return
    if (after == "status" && state.zoneIndex[serial]?.hasSensor) {
        runIn(1, "localPollWirelessDelayed", [data: [serial: serial], overwrite: false])
        return
    }
    if (after != "mhk2" && state.zoneIndex[serial]?.hasMhk2) {
        runIn(1, "localPollMhk2Delayed", [data: [serial: serial], overwrite: false])
        return
    }
    runIn(1, "localPollProfileDelayed", [data: [serial: serial], overwrite: false])
}

def localPollProfileDelayed(data) {
    localPollProfile(data?.serial as String)
}

def localPollAdapterDelayed(data) {
    localPollAdapter(data?.serial as String)
}

def applyLocalProfileResponse(String serial, Map parsed) {
    def profile = parsed?.r?.indoorUnit?.profile
    if (profile instanceof Map) {
        state.profiles[serial] = profile
        pushThermostatState(serial)
    }
    runIn(1, "localPollAdapterDelayed", [data: [serial: serial], overwrite: false])
}

def applyLocalAdapterResponse(String serial, Map parsed) {
    def adapterStatus = parsed?.r?.adapter?.status
    def adapterInfo = parsed?.r?.adapter?.info
    def status = (state.statusData[serial] instanceof Map) ? (state.statusData[serial] as Map) : [:]
    if (adapterStatus instanceof Map) {
        try {
            status.routerRssi = adapterStatus.localNetwork?.stationMode?.RSSI
        } catch (ignored) {}
        status.runState = adapterStatus.runState
        state.statusData[serial] = status
        pushDiagnosticState(serial)
        // Follow with adapter info for firmware/hardware (separate PUT).
        runIn(1, "localPollAdapterInfoDelayed", [data: [serial: serial], overwrite: false])
        return
    }
    if (adapterInfo instanceof Map) {
        status.firmwareVersion = adapterInfo.firmwareVersion
        status.hardwareVersion = adapterInfo.hardwareVersion
        state.statusData[serial] = status
        pushDiagnosticState(serial)
    }
}

def localPollAdapterInfoDelayed(data) {
    def serial = data?.serial as String
    if (serial) localPollAdapterInfo(serial)
}

def applyLocalWirelessResponse(String serial, Map parsed) {
    def sensors = parsed?.r?.sensors
    if (!(sensors instanceof Map)) return
    def matched = null
    sensors.each { k, v ->
        if (matched == null && v instanceof Map && v.uuid) matched = v
    }
    if (matched instanceof Map) {
        state.wirelessData[serial] = matched
        pushWirelessState(serial)
    }
}

def applyLocalMhk2Response(String serial, Map parsed) {
    def humid = null
    try {
        humid = parsed?.r?.mhk2?.status?.indoorHumid
    } catch (ignored) {}
    if (humid == null) return
    def device = (state.deviceData[serial] instanceof Map) ? (state.deviceData[serial] as Map) : [:]
    device.humidity = humid
    state.deviceData[serial] = device
    pushIndoorState(serial)
}

def handleLocalPollFailure(String serial) {
    def fails = ((state.failCounts[serial] ?: 0) as int) + 1
    state.failCounts[serial] = fails
    def cloudStatus = resolveCloudStatus(serial)
    if (cloudStatus != "online") {
        pushCloudStatus(serial, cloudStatus)
    }
    if (!state.cloudOffline && !localDegraded(serial)) {
        apiGet("/devices/${serial}", [requestType: "device", serial: serial])
    }
}

def handleLocalCommandResponse(String serial, Boolean ok) {
    state.commandInFlight[serial] = false
    if (ok) {
        state.lastCommandSend[serial] = now()
        state.lastCommandFields.remove(serial)
        logDebug "Local command accepted for ${tailSerial(serial)}"
        runIn(1, "refreshDeviceDetailLocalDelayed", [data: [serial: serial], overwrite: false])
        processNextCommand(serial)
        return
    }

    log.error "Local command failed for ${tailSerial(serial)}"
    def fields = state.lastCommandFields[serial]
    def restore = [:]
    if (fields instanceof List) {
        fields.each { field ->
            def cache = state.commandCache[serial]
            if (cache instanceof Map) {
                def entry = cache[field]
                if (entry instanceof Map && entry.value != null) {
                    restore[field] = entry.value
                }
            }
        }
    }
    clearFailedCommandCache(serial)

    if (restore.isEmpty()) {
        if (state.deviceData[serial]) pushStateToChildren(serial)
        if (state.cloudOffline) {
            runIn(1, "refreshDeviceDetailLocalDelayed", [data: [serial: serial], overwrite: false])
        }
        return
    }

    // Re-queue with cache so subsequent local failures can rebuild the payload again.
    if (!(state.commandPending[serial] instanceof Map)) state.commandPending[serial] = [:]
    restore.each { k, v ->
        state.commandPending[serial][k] = v
        cacheCommand(serial, k as String, v)
    }
    if (state.deviceData[serial]) pushStateToChildren(serial)

    if (state.cloudOffline) {
        runIn(1, "refreshDeviceDetailLocalDelayed", [data: [serial: serial], overwrite: false])
        def waitSec = Math.max(1L, (COMMAND_GAP_MS / 1000L) as long)
        runIn(waitSec, "processCommandQueue", [data: [serial: serial], overwrite: false])
        return
    }

    // Force cloud fallback for this command instead of tight local retries.
    state.localDegradedUntil[serial] = now() + LOCAL_DEGRADE_MS
    processNextCommand(serial)
}

def localPollStatus(String serial) {
    if (!canUseLocal(serial)) return
    localPut(serial, LOCAL_STATUS_QUERY, [requestType: "localStatus"])
}

def localPollProfile(String serial) {
    if (!canUseLocal(serial)) return
    localPut(serial, LOCAL_PROFILE_QUERY, [requestType: "localProfile"])
}

def localPollAdapter(String serial) {
    if (!canUseLocal(serial)) return
    localPut(serial, LOCAL_ADAPTER_STATUS_QUERY, [requestType: "localAdapter"])
}

def localPollAdapterInfo(String serial) {
    if (!canUseLocal(serial)) return
    localPut(serial, LOCAL_ADAPTER_INFO_QUERY, [requestType: "localAdapter"])
}

def localPollWireless(String serial) {
    if (!canUseLocal(serial)) return
    localPut(serial, '{"c":{"sensors":{"0":{}}}}', [requestType: "localWireless"])
}

def localPollMhk2(String serial) {
    if (!canUseLocal(serial)) return
    localPut(serial, LOCAL_MHK2_QUERY, [requestType: "localMhk2"])
}

def cloudToLocalField(String cloudField) {
    switch (cloudField) {
        case "operationMode": return "mode"
        case "airDirection": return "vaneDir"
        default: return cloudField
    }
}

def sendLocalCommands(String serial, Map cloudCommands) {
    def localStatus = [:]
    cloudCommands.each { k, v ->
        localStatus[cloudToLocalField(k as String)] = v
    }
    def body = JsonOutput.toJson([c: [indoorUnit: [status: localStatus]]])
    if (!localPut(serial, body, [requestType: "localCommand"])) {
        state.commandInFlight[serial] = false
        if (!(state.commandPending[serial] instanceof Map)) state.commandPending[serial] = [:]
        cloudCommands.each { k, v -> state.commandPending[serial][k] = v }
        processNextCommand(serial)
    }
}

// --- Socket.IO password fetch ---

def startSocketIoPasswordFetch() {
    if (state.cloudOffline || state.socketIoActive) return
    def need = []
    (state.knownSerials ?: []).each { serial ->
        def s = serial as String
        def creds = state.localCreds[s]
        if (!(creds instanceof Map) || !creds.password) need << s
    }
    if (need.isEmpty()) return
    state.socketIoActive = true
    state.socketIoNeed = need
    state.socketIoPasswords = [:]
    state.socketIoDeadline = now() + 60000L
    state.socketIoStep = "handshake"
    socketIoHandshake()
}

def socketIoHandshake() {
    try {
        httpGet([
            uri: "${SOCKET_BASE}/socket.io/",
            query: [EIO: "4", transport: "polling"],
            headers: ["Accept": "*/*", "Authorization": "Bearer ${atomicState.accessToken}"],
            timeout: 15
        ]) { response ->
            def text = response?.data?.toString() ?: ""
            if (!text.startsWith("0")) {
                finishSocketIo("handshake failed")
                return
            }
            def sid = new JsonSlurper().parseText(text.substring(1)).sid
            state.socketIoSid = sid as String
            state.socketIoStep = "connect"
            socketIoPost("40")
            runIn(1, "socketIoPoll", [overwrite: false])
        }
    } catch (Exception e) {
        finishSocketIo("handshake error: ${e.message}")
    }
}

def socketIoPost(String payload) {
    try {
        httpPost([
            uri: "${SOCKET_BASE}/socket.io/",
            query: [EIO: "4", transport: "polling", sid: state.socketIoSid],
            headers: [
                "Accept": "*/*",
                "Authorization": "Bearer ${atomicState.accessToken}",
                "Content-Type": "text/plain;charset=UTF-8"
            ],
            body: payload,
            timeout: 10
        ]) { response -> }
    } catch (Exception e) {
        log.warn "Socket.IO post failed: ${e.message}"
    }
}

def socketIoPoll() {
    if (!state.socketIoActive) return
    if (now() > (state.socketIoDeadline ?: 0L)) {
        finishSocketIo("timeout")
        return
    }
    try {
        httpGet([
            uri: "${SOCKET_BASE}/socket.io/",
            query: [EIO: "4", transport: "polling", sid: state.socketIoSid],
            headers: ["Accept": "*/*", "Authorization": "Bearer ${atomicState.accessToken}"],
            timeout: 25
        ]) { response ->
            def text = response?.data?.toString() ?: ""
            socketIoHandleText(text)
            if (state.socketIoActive) runIn(1, "socketIoPoll", [overwrite: false])
        }
    } catch (Exception e) {
        finishSocketIo("poll error: ${e.message}")
    }
}

def socketIoHandleText(String raw) {
    if (!raw) return
    def messages = raw.contains("\u001e") ? raw.split("\u001e") : [raw]
    messages.each { msg ->
        if (!msg) return
        if (msg.contains(":") && msg.split(":")[0].isNumber()) {
            msg = msg.split(":", 2)[1]
        }
        if (msg == "2") {
            socketIoPost("3")
            return
        }
        if (msg.startsWith("42")) {
            try {
                def payload = new JsonSlurper().parseText(msg.substring(2))
                if (payload instanceof List && payload.size() >= 2 && payload[0] == "adapter_update") {
                    def info = payload[1]
                    if (info instanceof Map) {
                        def s = info.deviceSerial as String
                        def pw = info.password as String
                        if (s && pw) {
                            ensureLocalCredEntry(s)
                            state.localCreds[s].password = pw
                            state.socketIoPasswords[s] = pw
                        }
                    }
                }
            } catch (ignored) {}
        }
    }
    if (state.socketIoStep == "connect") {
        state.socketIoStep = "subscribed"
        def userId = jwtUserId()
        if (userId) socketIoPost("42[\"subscribe\",\"\",\"${userId}\"]")
        (state.socketIoNeed ?: []).each { s ->
            socketIoPost("42[\"subscribe\",\"${s}\"]")
        }
        (state.socketIoNeed ?: []).each { s ->
            socketIoPost("42[\"force_adapter_request\",\"${s}\",\"adapterStatus\"]")
        }
        (state.socketIoNeed ?: []).each { s ->
            socketIoPost("42[\"device_status_v2\",\"${s}\"]")
        }
    }
    def need = state.socketIoNeed ?: []
    def got = (state.socketIoPasswords ?: [:]).keySet()
    if (need.every { it in got }) finishSocketIo("complete")
}

def jwtUserId() {
    try {
        def token = atomicState.accessToken as String
        if (!token) return null
        def parts = token.split("\\.")
        if (parts.size() < 2) return null
        // JWT uses URL-safe base64 (-/_); convert before standard decode.
        def payloadB64 = (parts[1] as String).replace("-", "+").replace("_", "/")
        def pad = payloadB64.length() % 4
        if (pad > 0) payloadB64 += "=" * (4 - pad)
        def json = new JsonSlurper().parseText(new String(payloadB64.decodeBase64(), "UTF-8"))
        return json?.id?.toString()
    } catch (Exception e) {
        return null
    }
}

def finishSocketIo(String reason) {
    state.socketIoActive = false
    log.info "Socket.IO password fetch finished: ${reason}"
    recomputeOfflineReady()
    if (settings.enableSubnetScan) startIpDiscovery()
}

// --- IP discovery ---

def startIpDiscovery() {
    def candidates = buildCandidateIpList()
    if (!candidates) return
    def serials = (state.knownSerials ?: []).findAll { serial ->
        def s = serial as String
        def creds = state.localCreds[s]
        return creds instanceof Map && creds.password && creds.cryptoSerial && !creds.address
    }
    if (!serials) return
    state.ipProbeSerials = serials
    state.ipProbeCandidates = candidates
    state.ipProbeSerialIdx = 0
    state.ipProbeIpIdx = 0
    scheduleNextIpProbe()
}

def scheduleNextIpProbe() {
    def serials = state.ipProbeSerials ?: []
    def candidates = state.ipProbeCandidates ?: []
    def sIdx = (state.ipProbeSerialIdx ?: 0) as int
    def iIdx = (state.ipProbeIpIdx ?: 0) as int

    if (!serials || sIdx >= serials.size()) {
        state.remove("ipProbeSerials")
        state.remove("ipProbeCandidates")
        state.remove("ipProbeSerialIdx")
        state.remove("ipProbeIpIdx")
        return
    }

    def serial = serials[sIdx] as String
    if (state.localCreds[serial]?.address) {
        state.ipProbeSerialIdx = sIdx + 1
        state.ipProbeIpIdx = 0
        runIn(1, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    if (iIdx >= candidates.size()) {
        state.ipProbeSerialIdx = sIdx + 1
        state.ipProbeIpIdx = 0
        runIn(1, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    if (state.localInFlight[serial]) {
        runIn(2, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    def ip = candidates[iIdx] as String
    state.ipProbeIpIdx = iIdx + 1
    state.ipProbeActive[serial] = true
    localPut(serial, LOCAL_STATUS_QUERY, [requestType: "localProbe", probeIp: ip, probeSerial: serial, overrideIp: ip])
    runIn(2, "scheduleNextIpProbe", [overwrite: true])
}

def buildCandidateIpList() {
    def subnet = settings.subnetOverride?.trim()
    if (!subnet) {
        def hubIp = location?.hub?.ipAddress as String
        if (!hubIp || !hubIp.contains(".")) return []
        def parts = hubIp.split("\\.")
        if (parts.size() != 4) return []
        subnet = "${parts[0]}.${parts[1]}.${parts[2]}"
    }
    def list = []
    for (int i = 1; i <= 254; i++) {
        list << "${subnet}.${i}"
    }
    return list
}

def logDebug(msg) {
    if (settings.debugLogging) log.info msg
}
