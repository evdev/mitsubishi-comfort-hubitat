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

definition(
    name: "Mitsubishi Comfort",
    namespace: "ephrayim",
    author: "ephrayim",
    description: "Cloud integration for Mitsubishi Comfort / Kumo Cloud HVAC zones",
    category: "Integrations",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat"
)

@Field static final String API_BASE = "https://app-prod.kumocloud.com"
@Field static final String API_VERSION = "v3"
@Field static final String API_APP_VERSION = "3.2.4"
@Field static final Long TOKEN_TTL_MS = 20L * 60L * 1000L
@Field static final Long TOKEN_MARGIN_MS = 5L * 60L * 1000L
@Field static final Long COMMAND_TTL_MS = 60L * 1000L
@Field static final Long COMMAND_GAP_MS = 5000L
@Field static final Integer STALE_FAIL_THRESHOLD = 3

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
        section("Diagnostics") {
            input name: "debugLogging", type: "bool", title: "Debug logging (auto-off after 30 min)", defaultValue: false
            href name: "cleanupPage", title: "Stale child cleanup", description: "List children no longer on your account"
        }
    }
}

def cleanupPage() {
    dynamicPage(name: "cleanupPage", title: "Stale child cleanup", uninstall: false, install: false) {
        section("Children marked stale") {
            def stale = getStaleChildren()
            if (!stale || stale.isEmpty()) {
                paragraph "No stale children. Stale devices are children whose zone no longer appears on your Comfort Cloud account."
            } else {
                paragraph "The following children are marked stale. Remove them from the Devices page if you no longer need them."
                stale.each { child ->
                    paragraph "${child.label ?: child.name} — DNI: ${child.deviceNetworkId}"
                }
            }
        }
    }
}

def installed() {
    log.info "Mitsubishi Comfort installed"
    initializeApp(true)
}

def updated() {
    log.info "Mitsubishi Comfort updated"
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

    if (settings.debugLogging) {
        runIn(1800, "debugOff", [overwrite: true])
    }

    if (!settings.username || !settings.password || !settings.siteId) {
        log.warn "Mitsubishi Comfort: credentials or site not configured"
        return
    }

    if (fullInit && state.setupAccessToken) {
        atomicState.accessToken = state.setupAccessToken
        atomicState.refreshToken = state.setupRefreshToken
        atomicState.tokenExpiresAt = now() + TOKEN_TTL_MS
        state.remove("setupAccessToken")
        state.remove("setupRefreshToken")
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

def tokenIsFresh() {
    return atomicState.accessToken && atomicState.tokenExpiresAt &&
        ((atomicState.tokenExpiresAt as long) > (now() + TOKEN_MARGIN_MS))
}

def onAuthSuccess() {
    def step = state.authNextStep
    state.authNextStep = null

    if (!state.pollingScheduled) {
        schedulePolling()
        state.pollingScheduled = true
    }

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
}

def failPendingAuthRequests() {
    def queue = (state.pendingHttpQueue instanceof List) ? (state.pendingHttpQueue as List) : []
    state.pendingHttpQueue = []
    queue.each { pending ->
        if (pending?.ctx?.requestType == "command" && pending.ctx.serial) {
            def serial = pending.ctx.serial as String
            state.commandInFlight[serial] = false
            // Restore commands so a later successful login can re-send them.
            if (!(state.commandPending[serial] instanceof Map)) state.commandPending[serial] = [:]
            if (pending.body?.commands instanceof Map) {
                pending.body.commands.each { k, v -> state.commandPending[serial][k] = v }
            }
            clearFailedCommandCache(serial)
            if (state.deviceData[serial]) pushStateToChildren(serial)
        }
    }
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
                failPendingAuthRequests()
            }
        }
    } catch (Exception e) {
        atomicState.refreshInProgress = false
        log.error "Auth callback error: ${e.message}"
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
}

def pollDevices() {
    refreshAllDeviceDetails()
}

def pollZones() {
    discoverZones()
}

def pollStatusAll() {
    (state.knownSerials ?: []).each { serial ->
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
    }
}

def pollNotificationsAll() {
    (state.zoneIndex ?: [:]).each { serial, info ->
        if (info?.zoneId) {
            apiGet("/zones/${info.zoneId}/notification-preferences", [
                requestType: "notifications", zoneId: info.zoneId, serial: serial
            ])
        }
    }
}

def pollProfilesAll() {
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
    (state.knownSerials ?: []).each { serial ->
        refreshDeviceDetail(serial as String)
    }
}

def refreshDeviceDetail(String serial) {
    if (!serial) return
    apiGet("/devices/${serial}", [requestType: "device", serial: serial])
    if (state.zoneIndex[serial]?.hasSensor) {
        apiGet("/devices/${serial}/sensor", [requestType: "wireless", serial: serial])
    }
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

        activeSerials << serial
        state.zoneIndex[serial] = [zoneId: zoneId, zoneName: zoneName, hasSensor: hasSensor]

        ensureZoneChildren(serial, zoneId, zoneName, hasSensor)
        refreshDeviceDetail(serial)
        apiGet("/devices/${serial}/profile", [requestType: "profile", serial: serial])
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
        apiGet("/zones/${zoneId}/notification-preferences", [
            requestType: "notifications", zoneId: zoneId, serial: serial
        ])
    }

    state.knownSerials = activeSerials as List
    markMissingSerialsStale(activeSerials)
}

def ensureZoneChildren(String serial, String zoneId, String zoneName, Boolean hasSensor) {
    ensureChild("mc-${serial}-thermostat", "Mitsubishi Comfort Thermostat", "${zoneName} Thermostat", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "thermostat"
    ])
    ensureChild("mc-${serial}-indoor", "Mitsubishi Comfort Indoor Sensor", "${zoneName} Indoor", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "indoor"
    ])
    ensureChild("mc-${serial}-diag", "Mitsubishi Comfort Diagnostics", "${zoneName} Diagnostics", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "diag"
    ])
    ensureChild("mc-${zoneId}-filter", "Mitsubishi Comfort Filter Reminder", "${zoneName} Filter", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "filter"
    ])
    if (hasSensor) {
        ensureChild("mc-${serial}-wireless", "Mitsubishi Comfort Wireless Sensor", "${zoneName} Wireless", [
            deviceSerial: serial, zoneId: zoneId, deviceType: "wireless"
        ])
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
    getChildDevices()?.each { child ->
        def serial = child.getDataValue("deviceSerial")
        if (serial && !(serial in activeSerials)) {
            child.sendEvent(name: "cloudStatus", value: "stale")
        }
    }
}

def getStaleChildren() {
    return getChildDevices()?.findAll { it.currentValue("cloudStatus") == "stale" } ?: []
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
        def device = state.deviceData[serial]
        def cloudStatus = "online"
        if (device instanceof Map && device.connected == false) {
            cloudStatus = "offline"
        } else if (fails >= STALE_FAIL_THRESHOLD) {
            cloudStatus = "stale"
        }
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
    getChildrenForSerial(serial).each { child ->
        child.sendEvent(name: "cloudStatus", value: cloudStatus)
    }
}

def getChildrenForSerial(String serial) {
    return getChildDevices()?.findAll { it.getDataValue("deviceSerial") == serial } ?: []
}

def pushThermostatState(String serial) {
    def child = getChildDevice("mc-${serial}-thermostat")
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
    def connected = device.connected != false
    def cloudStatus = connected ?
        (((state.failCounts[serial] ?: 0) as int) >= STALE_FAIL_THRESHOLD ? "stale" : "online") :
        "offline"

    def supportedModes = buildSupportedModes(profile)
    def supportedFans = JsonOutput.toJson(buildSupportedFanModes(profile))
    def setpointLimits = buildSetpointLimits(profile)

    def singleSp = null
    if (hubMode == "heat") singleSp = heatSp
    else if (hubMode == "cool") singleSp = coolSp
    else if (hubMode == "auto") singleSp = coolSp ?: heatSp

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
            model: device.model?.materialDescription,
            serialNumber: device.serialNumber
        ]
        stateMap.putAll(setpointLimits)
        child.applyThermostatState(stateMap)
    } catch (Exception e) {
        log.error "pushThermostatState failed for ${tailSerial(serial)}: ${e.message}"
    }
}

def pushIndoorState(String serial) {
    def child = getChildDevice("mc-${serial}-indoor")
    if (!child) return
    def device = state.deviceData[serial]
    if (!(device instanceof Map)) return
    def map = [
        temperature: cToDisplay(device.roomTemp),
        tempUnit: useFahrenheit() ? "°F" : "°C",
        cloudStatus: device.connected == false ? "offline" : "online"
    ]
    if (device.humidity != null) map.humidity = device.humidity
    try {
        child.applyIndoorState(map)
    } catch (Exception e) {
        log.error "pushIndoorState failed: ${e.message}"
    }
}

def pushWirelessState(String serial) {
    def child = getChildDevice("mc-${serial}-wireless")
    if (!child) return
    def sensor = state.wirelessData[serial]
    if (!(sensor instanceof Map)) return
    def tempC = sensor.temperature
    def temp = null
    if (tempC != null) {
        temp = useFahrenheit() ?
            Math.round((((tempC as double) * 9.0 / 5.0) + 32.0) * 10.0) / 10.0 :
            Math.round((tempC as double) * 10.0) / 10.0
    }
    try {
        child.applyWirelessState([
            temperature: temp,
            tempUnit: useFahrenheit() ? "°F" : "°C",
            humidity: sensor.humidity != null ? Math.round((sensor.humidity as double) * 10) / 10.0 : null,
            battery: sensor.battery,
            rssi: sensor.rssi,
            cloudStatus: "online"
        ])
    } catch (Exception e) {
        log.error "pushWirelessState failed: ${e.message}"
    }
}

def pushDiagnosticState(String serial) {
    def child = getChildDevice("mc-${serial}-diag")
    if (!child) return
    def status = state.statusData[serial]
    if (!(status instanceof Map)) return
    try {
        child.applyDiagnosticState([
            rssi: status.routerRssi,
            firmwareVersion: status.firmwareVersion,
            routerSsid: status.routerSsid,
            cloudStatus: "online"
        ])
    } catch (Exception e) {
        log.error "pushDiagnosticState failed: ${e.message}"
    }
}

def pushFilterState(String serial) {
    def zoneId = state.zoneIndex[serial]?.zoneId
    if (!zoneId) return
    def child = getChildDevice("mc-${zoneId}-filter")
    if (!child) return
    def notifications = state.notificationData[zoneId]
    if (!(notifications instanceof Map)) return
    try {
        child.applyFilterState([
            lastFilterReminder: notifications.filterDirtyReminderLastSent,
            reminderIntervalDays: notifications.filterDirtyReminderInterval,
            remindersEnabled: notifications.filterDirty != null ? notifications.filterDirty.toString() : null,
            cloudStatus: "online"
        ])
    } catch (Exception e) {
        log.error "pushFilterState failed: ${e.message}"
    }
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

    if (state.commandInFlight[serial]) {
        runIn(2, "processCommandQueue", [data: [serial: serial], overwrite: false])
        return
    }

    def pending = state.commandPending[serial]
    if (!(pending instanceof Map) || pending.isEmpty()) return

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
        // Auth deferral queued the request; keep inFlight false until replay starts.
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
    refreshDeviceDetail(serial)
    apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
    def zoneId = child.getDataValue("zoneId")
    if (zoneId) {
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

def logDebug(msg) {
    if (settings.debugLogging) log.debug msg
}
