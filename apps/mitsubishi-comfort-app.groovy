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
@Field static final String LEGACY_API_BASE = "https://geo-c.kumocloud.com"
@Field static final String LEGACY_APP_VERSION = "2.2.0"
@Field static final String LEGACY_APP_KEY = "49;2;11;0;10;11;0;10;13;5;15;12;8;13;6;8;11;12;9;11;12;11;13;4;9;13;8;8"
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
@Field static final Integer IP_PROBE_HOST_MAX = 254
@Field static final Integer CRYPTO_SERIAL_MIN_BYTES = 9
@Field static final Integer LOCAL_SENSOR_INDEX_MAX = 3
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
    page(name: "ipScanPage")
    page(name: "credFetchPage")
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
    ensureSubnetSetting()
    backfillIpSettingsFromState()

    dynamicPage(name: "mainPage", title: "Mitsubishi Comfort", install: true, uninstall: true) {
        section("Comfort Cloud credentials") {
            input name: "username", type: "text", title: "Email", required: true, submitOnChange: true
            input name: "password", type: "password", title: "Password", required: true, submitOnChange: true
            if (state.loginError) {
                paragraph "Login error: ${state.loginError}"
            } else if (state.setupSitesLoaded) {
                if (tokenIsFresh()) {
                    paragraph "Login successful. Select your site below."
                } else {
                    paragraph "Comfort Cloud account saved. The app will reconnect when you tap Done or Fetch local device keys."
                }
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
            paragraph "Each zone needs two things for local control: a local device key (fetched automatically — you never type it) and a LAN IP (type it below, or scan). Cloud control works without either."
            input name: "preferLocal", type: "bool", title: "Prefer local LAN control", defaultValue: true
            input name: "allowOffline", type: "bool", title: "Allow offline local control when internet is down", defaultValue: true
            if (state.offlineReady) {
                paragraph "Offline ready: cached device keys and IPs allow local control without internet."
            } else if (state.knownSerials instanceof List && !state.knownSerials.isEmpty()) {
                paragraph "Offline not ready: fetch local device keys, then enter or scan unit IPs below."
            }
            if (state.cloudOffline) {
                paragraph "Operating in offline local-only mode (cloud unreachable)."
            }
            if (state.lastCloudContact) {
                paragraph "Last cloud contact: ${state.lastCloudContact}"
            }

            paragraph "Step 1 — Local device keys. These come from your Comfort Cloud login automatically. There is no password to type here. Tap Fetch local device keys, then wait about 15–30 seconds."
            def fetching = credFetchRunning()
            input name: "btnRefreshCreds", type: "button", title: "Fetch local device keys", disabled: fetching == true
            href name: "credFetchPageHref", page: "credFetchPage", title: fetching ? "Watch device key progress" : "Device key status",
                description: credFetchHrefDescription(), state: allDeviceKeysFound() ? "complete" : ""
            def credSnapshot = credFetchSnapshotText()
            if (credSnapshot) paragraph credSnapshot

            paragraph "Step 2 — Unit LAN IPs. The boxes below are IP addresses only (for example 192.168.1.44), not passwords. DHCP reservations on your router are recommended. Scan is optional."
            if (state.zoneIndex instanceof Map && !state.zoneIndex.isEmpty()) {
                state.zoneIndex.each { serial, info ->
                    def s = serial as String
                    def ipKey = unitIpSettingKey(s)
                    def stored = settings[ipKey]?.toString()?.trim()
                    def discovered = state.localCreds?.get(s)?.address?.toString()?.trim()
                    def shown = stored ?: discovered
                    input name: ipKey, type: "text", title: "${info?.zoneName ?: s} IP", defaultValue: shown ?: "", required: false
                    paragraph unitIpStatusLine(s)
                    paragraph unitDeviceKeyStatusLine(s)
                }
            }
            input name: "enableSubnetScan", type: "bool", title: "Automatically scan for missing IPs after login", defaultValue: false, submitOnChange: true
            def hubIp = hubLocalIp()
            def hubPrefix = hubSubnetPrefix()
            input name: "subnetOverride", type: "text", title: "Subnet to scan (e.g. 192.168.1)", defaultValue: hubPrefix ?: "", required: false
            if (hubIp && hubPrefix) {
                paragraph "Subnet is pre-filled from this hub (${hubIp}). Change only if units are on a different LAN. Use the first three octets, e.g. ${hubPrefix}."
            } else {
                paragraph "Enter the first three octets of the LAN the units are on, e.g. 192.168.1."
            }
            def snapshot = ipScanSnapshotText()
            if (snapshot) paragraph snapshot
            def scanning = state.ipScan?.status == "running"
            input name: "btnFindMissingIps", type: "button", title: "Find missing unit IPs", disabled: scanning == true
            href name: "ipScanPageHref", page: "ipScanPage", title: scanning ? "Watch scan progress" : "Find unit IPs",
                description: ipScanHrefDescription(), state: allUnitIpsFound() ? "complete" : ""
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

def ipScanPage() {
    def scan = ipScanMap()
    def running = scan.status == "running"
    dynamicPage(name: "ipScanPage", title: "Find unit IPs", uninstall: false, install: false, refreshInterval: running ? 4 : 0) {
        section("Scan") {
            def subnet = resolvedSubnetPrefix()
            paragraph "Probes ${(subnet ?: 'your subnet')}.x for units that do not yet have an IP. Fetch local device keys first (Step 1 on the settings page) — the scan authenticates with each unit and cannot run without those keys. A full subnet scan can take several minutes."
            if (scan.message) paragraph scan.message as String
            if (running) {
                paragraph ipScanProgressBar()
                if (scan.currentIp) {
                    paragraph "Current: ${scan.currentIp} (${zoneDisplayName(scan.currentSerial as String)})"
                }
            }
            input name: "btnFindMissingIps", type: "button", title: running ? "Scan running…" : "Start scan", disabled: running == true
            if (running) {
                input name: "btnCancelIpScan", type: "button", title: "Cancel scan"
            }
        }
        section("Units") {
            renderIpScanUnitStatus()
        }
        section("Back") {
            href name: "backToMainFromScan", page: "mainPage", title: "Back to settings"
        }
    }
}

def credFetchPage() {
    def fetch = credFetchMap()
    def running = fetch.status == "running"
    dynamicPage(name: "credFetchPage", title: "Local device keys", uninstall: false, install: false, refreshInterval: running ? 4 : 0) {
        section("Fetch") {
            paragraph "Local device keys are retrieved automatically from Comfort Cloud using the email and password you already entered above. You never type a device key. This usually takes 15–30 seconds."
            if (fetch.message) paragraph fetch.message as String
            if (running) {
                def found = (fetch.found ?: 0) as int
                def total = (fetch.total ?: 0) as int
                paragraph "Received ${found} of ${total}."
            }
            input name: "btnRefreshCreds", type: "button", title: running ? "Fetching…" : "Fetch local device keys", disabled: running == true
            if (running) {
                input name: "btnCancelCredFetch", type: "button", title: "Cancel fetch"
            }
        }
        section("Units") {
            renderCredFetchUnitStatus()
        }
        section("Back") {
            href name: "backToMainFromCreds", page: "mainPage", title: "Back to settings"
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case "btnRefreshCreds":
            if (!ensureCloudAuthForUi()) {
                setCredFetchBlocked("Could not sign in to Comfort Cloud with the email and password at the top of this page. Check them and try again.")
                log.warn "Cannot fetch local device keys: Comfort Cloud sign-in failed"
            } else {
                startSocketIoPasswordFetch(true)
            }
            break
        case "btnCancelCredFetch":
            cancelCredFetch()
            break
        case "btnFindMissingIps":
            startIpDiscovery()
            break
        case "btnCancelIpScan":
            cancelIpDiscovery()
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
    state.socketIoActive = false
    atomicState.sio = null
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

    def credKey = "${settings.username ?: ''}:${settings.password ? 'set' : ''}"
    def credsChanged = state.appliedCredKey && state.appliedCredKey != credKey
    if (credsChanged) {
        clearRuntimeAuth()
    }
    if (state.setupAccessToken) {
        atomicState.accessToken = state.setupAccessToken
        atomicState.refreshToken = state.setupRefreshToken
        def issuedAt = (state.setupTokenIssuedAt ?: now()) as long
        atomicState.tokenExpiresAt = issuedAt + TOKEN_TTL_MS
        state.remove("setupAccessToken")
        state.remove("setupRefreshToken")
        state.remove("setupTokenIssuedAt")
        state.appliedCredKey = credKey
    } else if (!state.appliedCredKey || credsChanged) {
        state.appliedCredKey = credKey
    }

    if (!tokenIsFresh() && !credsChanged && offlineOperationAllowed()) {
        state.cloudOffline = true
        onOfflineBoot(fullInit ? "initialDiscover" : "resume")
        // Fully local-ready: stay offline. Partial: still try cloud for the rest.
        if (state.offlineReady) return
    }

    runtimeLogin(fullInit ? "initialDiscover" : "resume")
}

def debugOff() {
    app.updateSetting("debugLogging", [type: "bool", value: false])
    log.info "Mitsubishi Comfort debug logging disabled"
}

def clearRuntimeAuth() {
    atomicState.accessToken = null
    atomicState.refreshToken = null
    atomicState.tokenExpiresAt = null
    atomicState.refreshInProgress = false
    state.pendingHttpQueue = []
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
    if (!(state.ipProbeActive instanceof Map)) state.ipProbeActive = [:]
    if (!(state.ipScan instanceof Map)) state.ipScan = [:]
    if (!(state.credFetch instanceof Map)) state.credFetch = [:]
}

// --- Setup-time synchronous auth ---

def loadSetupSites() {
    state.loginError = null
    def login = syncComfortCloudLogin()
    if (!login.ok) {
        state.loginError = login.error ?: "Login failed"
        return
    }

    try {
        def sitesResp = null
        httpGet([
            uri: "${API_BASE}/${API_VERSION}/sites/",
            headers: authHeaders(atomicState.accessToken),
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

def applyAccessTokens(String access, String refresh, Long issuedAt) {
    atomicState.accessToken = access
    atomicState.refreshToken = refresh
    atomicState.tokenExpiresAt = ((issuedAt ?: now()) as long) + TOKEN_TTL_MS
    atomicState.refreshInProgress = false
    state.cloudOffline = false
}

def adoptSetupTokens() {
    if (!state.setupAccessToken) return false
    if (hasFreshAccessToken()) return true
    def issuedAt = (state.setupTokenIssuedAt ?: now()) as long
    applyAccessTokens(
        state.setupAccessToken as String,
        state.setupRefreshToken as String,
        issuedAt
    )
    return (issuedAt + TOKEN_TTL_MS) > (now() + TOKEN_MARGIN_MS)
}

def hasFreshAccessToken() {
    return atomicState.accessToken && atomicState.tokenExpiresAt &&
        ((atomicState.tokenExpiresAt as long) > (now() + TOKEN_MARGIN_MS))
}

def currentAccessToken() {
    def token = atomicState.accessToken as String
    if (token) return token
    return state.setupAccessToken as String
}

def ensureCloudAuthForUi() {
    if (hasFreshAccessToken()) {
        state.cloudOffline = false
        return true
    }
    if (adoptSetupTokens()) {
        state.cloudOffline = false
        return true
    }
    if (!settings.username || !settings.password) return false
    def login = syncComfortCloudLogin()
    return login.ok == true
}

def syncComfortCloudLogin() {
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
            return [ok: false, error: loginResp ? "HTTP ${loginResp.status}" : "No response"]
        }

        def loginJson = parseMaybeJson(loginResp.data)
        if (!loginJson?.token?.access) {
            return [ok: false, error: "Login response missing token"]
        }
        state.setupAccessToken = loginJson.token.access
        state.setupRefreshToken = loginJson.token.refresh
        state.setupTokenIssuedAt = now()
        applyAccessTokens(loginJson.token.access as String, loginJson.token.refresh as String, now())
        captureKumoUserId(loginJson)
        return [ok: true]
    } catch (Exception e) {
        log.error "Comfort Cloud login failed: ${e.message}"
        return [ok: false, error: e.message]
    }
}

// --- Runtime auth ---

def runtimeLogin(String nextStep) {
    state.authNextStep = nextStep
    if (state.cloudOffline && state.offlineReady && offlineOperationAllowed()) {
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
    log.warn "Mitsubishi Comfort: cloud offline — using local control where credentials are complete"
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
    if (hasFreshAccessToken()) return true
    return adoptSetupTokens()
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
    if (sioState().active != true) {
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
            captureKumoUserId(json)
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
        (state.knownSerials ?: []).each { serial ->
            if (!canUseLocal(serial as String)) {
                refreshDeviceDetail(serial as String)
            }
        }
        return
    }
    refreshAllDeviceDetails()
}

def pollZones() {
    if (state.cloudOffline && state.offlineReady) return
    discoverZones()
}

def pollStatusAll() {
    if (state.cloudOffline) {
        (state.knownSerials ?: []).each { serial ->
            def s = serial as String
            if (canUseLocal(s)) {
                localPollAdapter(s)
            } else {
                apiGet("/devices/${s}/status", [requestType: "status", serial: s])
            }
        }
        return
    }
    (state.knownSerials ?: []).each { serial ->
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
    }
}

def pollNotificationsAll() {
    if (state.cloudOffline && state.offlineReady) return
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
            def s = serial as String
            if (canUseLocal(s)) {
                localPollProfile(s)
            } else {
                apiGet("/devices/${s}/profile", [requestType: "profile", serial: s])
            }
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
        if (!hasSensor && state.wirelessData instanceof Map) {
            state.wirelessData.remove(serial)
        }
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
    if (p.hasModeHeat != false && (p.hasModeHeat || p.maximumSetPoints?.heat != null)) modes << "heat"
    if (p.hasModeCool || p.maximumSetPoints?.cool != null) modes << "cool"
    if (p.hasModeDry) modes << "dry"
    if (p.hasModeFan || p.hasModeVent) modes << "fan"
    if (p.hasModeAuto != false && (p.hasModeAuto || p.maximumSetPoints?.auto != null)) modes << "auto"
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
    if (hubMode == "fan") return "fan only"
    if (room == null) {
        if (hubMode == "heat") return "heating"
        if (hubMode == "cool" || hubMode == "dry") return "cooling"
        return "idle"
    }

    def deadBand = useFahrenheit() ? 1.0d : 0.5d
    def roomN = room as double
    def op = device?.operationMode

    if (hubMode == "heat") {
        if (heatSp != null && roomN < ((heatSp as double) - deadBand)) return "heating"
        return heatSp != null ? "idle" : "heating"
    }
    if (hubMode == "cool" || hubMode == "dry") {
        if (coolSp != null && roomN > ((coolSp as double) + deadBand)) return "cooling"
        return coolSp != null ? "idle" : "cooling"
    }
    if (hubMode != "auto") return "idle"

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
    // Map literals store 21.5 as BigDecimal; roundHalf() returns Double. Lookup must use BigDecimal.
    def mapped = C_TO_F[c as BigDecimal]
    if (mapped != null) return mapped
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
        if (field == "operationMode") {
            state.deviceData[serial].power = (value as String) == "off" ? 0 : 1
        }
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

    if (state.cloudOffline && !tokenIsFresh()) {
        def waitMs = Math.max(30000L, COMMAND_GAP_MS)
        def lastAttempt = (state.lastOfflineCloudAttempt ?: 0L) as long
        def sinceAttempt = now() - lastAttempt
        if (sinceAttempt < waitMs) {
            def waitSec = Math.max(1L, ((waitMs - sinceAttempt) / 1000L) as long)
            log.warn "Cannot send command for ${tailSerial(serial)}: cloud offline and local credentials incomplete; retrying in ${waitSec}s"
            runIn(waitSec, "processCommandQueue", [data: [serial: serial], overwrite: false])
            return
        }
        state.lastOfflineCloudAttempt = now()
        log.warn "Retrying cloud command for ${tailSerial(serial)} while other units stay on local"
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
    if (canUseLocal(serial)) {
        refreshDeviceDetailLocal(serial)
        localPollAdapter(serial)
    } else {
        refreshDeviceDetail(serial)
        apiGet("/devices/${serial}/status", [requestType: "status", serial: serial])
    }
    def zoneId = child.getDataValue("zoneId")
    if (zoneId && !(state.cloudOffline && state.offlineReady)) {
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
        if (ip) {
            ensureLocalCredEntry(s)
            state.localCreds[s].address = ip
            state.localCreds[s].addressLocked = true
        } else {
            def stateIp = state.localCreds?.get(s)?.address?.toString()?.trim()
            if (stateIp) {
                app.updateSetting(key, [type: "text", value: stateIp])
            }
        }
    }
    recomputeOfflineReady()
}

def ensureLocalCredEntry(String serial) {
    if (!(state.localCreds instanceof Map)) state.localCreds = [:]
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
    if (state.localInFlight[serial]) {
        return deferBusyLocalPut(serial, bodyStr, ctx)
    }
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

def deferBusyLocalPut(String serial, String bodyStr, Map ctx) {
    def type = ctx?.requestType as String
    if (type == "localCommand" || type == "localProbe") return false
    def busyRetry = (ctx?.busyRetry ?: 0) as int
    if (busyRetry >= 8) {
        log.warn "Dropped local ${type} for ${tailSerial(serial)}: adapter still busy"
        return false
    }
    runIn(2, "localPutBusyRetry", [
        data: [serial: serial, bodyStr: bodyStr, ctx: (ctx ?: [:]) + [busyRetry: busyRetry + 1]],
        overwrite: false
    ])
    return true
}

def localPutBusyRetry(data) {
    def serial = data?.serial as String
    if (!serial) return
    localPut(serial, data.bodyStr as String, (data.ctx ?: [:]) as Map)
}

def localHttpCallback(response, data) {
    def serial = data?.serial as String
    if (!serial) return
    def status = safeStatus(response)
    def parsed = (status >= 200 && status < 300) ? parseAsyncBody(response) : null
    def ok = parsed instanceof Map && parsed.r != null

    def isProbe = data.requestType == "localProbe"
    if (!ok && !isProbe && (data.retry as int) < 1 && (status == 0 || status == 408)) {
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
        if (!isProbe) {
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
            def found = parsed ? applyLocalWirelessResponse(serial, parsed) : false
            def idx = (data.sensorIndex ?: 0) as int
            if (!found && parsed && idx < LOCAL_SENSOR_INDEX_MAX) {
                runIn(1, "localPollWirelessDelayed", [data: [serial: serial, sensorIndex: idx + 1], overwrite: false])
                return
            }
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
            handleLocalProbeResponse(serial, parsed, data)
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
    // Overlay optimistic commands after raw status so a lagging adapter does not snap the UI back.
    applyCommandCacheToDevice(serial, device)
    device.power = (device.operationMode == "off" || device.operationMode == null) ? 0 : 1
    state.deviceData[serial] = device
    state.failCounts[serial] = 0
    pushStateToChildren(serial)
    scheduleNextLocalPoll(serial, "status")
}

def localPollWirelessDelayed(data) {
    def serial = data?.serial as String
    if (serial) localPollWireless(serial, (data.sensorIndex ?: 0) as int)
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
        applyInstallerModeLocks(serial, adapterStatus)
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

def applyInstallerModeLocks(String serial, Map adapterStatus) {
    if (!serial || !(adapterStatus instanceof Map)) return
    def stored = state.profiles[serial]
    def profile = normalizeProfile(stored)
    if (!profile) return

    def changed = false
    if (adapterStatus.containsKey("userHasModeDry") && !adapterStatus.userHasModeDry && profile.hasModeDry != false) {
        profile.hasModeDry = false
        changed = true
    }
    if (adapterStatus.containsKey("userHasModeHeat") && !adapterStatus.userHasModeHeat && profile.hasModeHeat != false) {
        profile.hasModeHeat = false
        changed = true
    }
    if (adapterStatus.autoModePrevention) {
        def maxSp = (profile.maximumSetPoints instanceof Map) ? profile.maximumSetPoints : [:]
        def minSp = (profile.minimumSetPoints instanceof Map) ? profile.minimumSetPoints : [:]
        if (maxSp.auto == null && minSp.auto == null && profile.hasModeAuto != false) {
            profile.hasModeAuto = false
            changed = true
        }
    }
    if (!changed) return
    if (stored instanceof List) {
        stored[0] = profile
        state.profiles[serial] = stored
    } else {
        state.profiles[serial] = profile
    }
    pushThermostatState(serial)
}

def applyLocalWirelessResponse(String serial, Map parsed) {
    def sensors = parsed?.r?.sensors
    if (!(sensors instanceof Map)) return false
    def matched = null
    sensors.each { k, v ->
        if (matched == null && v instanceof Map && v.uuid) matched = v
    }
    if (!(matched instanceof Map)) return false
    state.wirelessData[serial] = matched
    pushWirelessState(serial)
    return true
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

    if (!restore.isEmpty()) {
        if (!(state.commandPending[serial] instanceof Map)) state.commandPending[serial] = [:]
        restore.each { k, v ->
            state.commandPending[serial][k] = v
            cacheCommand(serial, k as String, v)
        }
    }
    if (state.deviceData[serial]) pushStateToChildren(serial)

    if (state.cloudOffline) {
        runIn(1, "refreshDeviceDetailLocalDelayed", [data: [serial: serial], overwrite: false])
        def waitSec = Math.max(1L, (COMMAND_GAP_MS / 1000L) as long)
        runIn(waitSec, "processCommandQueue", [data: [serial: serial], overwrite: false])
        return
    }

    // Force cloud fallback instead of tight local retries, even when the cache had nothing to restore.
    state.localDegradedUntil[serial] = now() + LOCAL_DEGRADE_MS
    if (restore.isEmpty()) {
        refreshDeviceDetail(serial)
    }
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

def localPollWireless(String serial, Integer index = 0) {
    if (!canUseLocal(serial)) return
    def idx = (index ?: 0) as int
    localPut(serial, "{\"c\":{\"sensors\":{\"${idx}\":{}}}}", [requestType: "localWireless", sensorIndex: idx])
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

def sioState() {
    def s = atomicState.sio
    return (s instanceof Map) ? ([:] + (s as Map)) : [:]
}

def sioStateSet(Map sio) {
    atomicState.sio = sio
    state.socketIoActive = sio.active == true
    state.socketIoNeed = sio.need
    state.socketIoPasswords = sio.got
    state.socketIoSid = sio.sid
    state.socketIoDeadline = sio.deadline
    state.socketIoStep = sio.step
}

def startSocketIoPasswordFetch(Boolean forceAll = false, Boolean isRetry = false) {
    def existing = sioState()
    if (existing.active == true) {
        def deadline = (existing.deadline ?: 0L) as long
        if (!forceAll && now() <= deadline) return
        log.info "Resetting previous device-key fetch before starting a new one"
        existing.active = false
        sioStateSet(existing)
    }
    if (state.cloudOffline) {
        if (forceAll) {
            setCredFetchBlocked("Cloud is unreachable. Local device keys can only be fetched while online.")
        }
        return
    }
    def need = []
    (state.knownSerials ?: []).each { serial ->
        def s = serial as String
        def creds = state.localCreds[s]
        if (forceAll || !(creds instanceof Map) || !creds.password) need << s
    }
    if (need.isEmpty()) {
        if (forceAll) {
            def known = state.knownSerials ?: []
            if (!(known instanceof List) || known.isEmpty()) {
                if (settings.siteId && tokenIsFresh()) {
                    persistCredFetch([
                        status: "running",
                        message: "Discovering zones first. Device keys will fetch automatically after that.",
                        total: 0,
                        found: 0
                    ])
                    discoverZones()
                    return
                }
                setCredFetchBlocked("No zones discovered yet. Select your site above, then tap Done.")
            } else {
                persistCredFetch([
                    status: "complete",
                    message: "All zones already have a local device key.",
                    total: known.size(),
                    found: known.size()
                ])
            }
        }
        return
    }
    sioStateSet([
        active: true,
        need: need,
        got: [:],
        deadline: now() + 120000L,
        step: "open",
        retryOnce: isRetry == true,
        nsWaitTries: 0
    ])
    persistCredFetch([
        status: "running",
        message: "Fetching local device keys from Comfort Cloud…",
        total: need.size(),
        found: 0,
        startedAt: now()
    ])
    runIn(30, "socketIoWatchdog", [overwrite: true])
    socketIoStartAsync()
}

def socketIoStartAsync() {
    updateCredFetchMessage("Connecting to Comfort Cloud…")
    socketIoSend("GET", null, "open")
}

def socketIoHeaders() {
    return [
        "Accept": "text/plain, */*",
        "Authorization": "Bearer ${currentAccessToken()}",
        "User-Agent": "kumocloud/1122"
    ]
}

def socketIoSend(String method, String body, String step) {
    def sio = sioState()
    if (sio.active != true) return
    sio.step = step
    sioStateSet(sio)
    def query = [EIO: "4", transport: "polling", t: now().toString()]
    if (step != "open" && sio.sid) query.sid = sio.sid.toString()
    def rawBody = (body ?: "").toString()
    def params = [
        uri: "${SOCKET_BASE}/socket.io/",
        query: query,
        headers: socketIoHeaders(),
        contentType: "text/plain",
        requestContentType: "text/plain",
        timeout: (method == "GET") ? 30 : 15
    ]
    if (method == "POST") {
        params.headers = socketIoHeaders() + ["Content-Type": "text/plain;charset=UTF-8"]
        params.body = rawBody
        log.info "Socket.IO POST ${step} chars=${rawBody.length()} rs=${rawBody.contains('\u001e')}"
    }
    def data = [step: step, method: method, sid: (sio.sid ?: "")]
    try {
        if (method == "POST") asynchttpPost("socketIoAsyncCallback", params, data)
        else asynchttpGet("socketIoAsyncCallback", params, data)
    } catch (Exception e) {
        finishSocketIo("send error: ${e.message}")
    }
}

def socketIoAsyncParsed(response) {
    try {
        if (response?.respondsTo("getJson")) {
            def json = response.getJson()
            if (json instanceof Map || json instanceof List) return json
        }
    } catch (ignored) {}
    try {
        def d = null
        if (response?.respondsTo("getData")) d = response.getData()
        else if (response?.hasProperty("data")) d = response.data
        if (d instanceof Map || d instanceof List) return d
    } catch (ignored) {}
    return null
}

def socketIoAsyncText(response, parsed) {
    def chunks = []
    try {
        def d = null
        if (response?.respondsTo("getData")) d = response.getData()
        else if (response?.hasProperty("data")) d = response.data
        if (d instanceof Map || d instanceof List) {
            // parsed separately
        } else if (d instanceof byte[]) {
            chunks << new String(d, "UTF-8")
        } else if (d) {
            chunks << d.toString()
        }
    } catch (ignored) {}
    try {
        if (response?.respondsTo("getErrorData")) {
            def err = response.getErrorData()
            if (err) chunks << err.toString()
        }
    } catch (ignored) {}
    try {
        if (response?.respondsTo("getErrorMessage")) {
            def err = response.getErrorMessage()
            if (err && err != "null") chunks << err.toString()
        }
    } catch (ignored) {}
    def text = chunks.find { it } ?: ""
    if (!text && parsed != null) {
        try { text = JsonOutput.toJson(parsed) } catch (ignored) {}
    }
    return text
}

def socketIoParsedKind(parsed) {
    if (parsed instanceof Map) return "map"
    if (parsed instanceof List) return "list"
    if (parsed != null) return "yes"
    return "none"
}

def socketIoLogSnippet(String text) {
    if (!text) return "(empty)"
    def snippet = text.take(180)
    snippet = snippet.replaceAll("(?i)\"password\"\\s*:\\s*\"[^\"]*\"", "\"password\":\"***\"")
    snippet = snippet.replaceAll("(?i)\"token\"\\s*:\\s*\"[^\"]*\"", "\"token\":\"***\"")
    return snippet.replace("\n", " ")
}

def socketIoHasHttpError(response) {
    try {
        return response?.respondsTo("hasError") && response.hasError()
    } catch (ignored) {
        return false
    }
}

def socketIoAsyncCallback(response, data) {
    def sio = sioState()
    if (sio.active != true) return
    def step = (data?.step ?: sio.step) as String
    def method = (data?.method ?: "?") as String
    def status = safeStatus(response)
    def parsed = socketIoAsyncParsed(response)
    def text = socketIoAsyncText(response, parsed)
    def errFlag = socketIoHasHttpError(response)
    log.info "Socket.IO ${method} ${step} HTTP ${status} err=${errFlag} parsed=${socketIoParsedKind(parsed)} len=${text.length()} ${socketIoLogSnippet(text)}"

    if (status == 401) {
        socketIoRetryAuth("unauthorized")
        return
    }

    def timedOut = (status == 408 || status == 0 || (errFlag && status != 200 && !text && parsed == null))
    if (step == "open" && status != 200 && !timedOut) {
        finishSocketIo("handshake HTTP ${status}")
        return
    }
    if (status == 400) {
        log.warn "Socket.IO session error at ${step}: ${socketIoLogSnippet(text)}"
        socketIoRetryAuth("session HTTP 400")
        return
    }

    extractSocketIoPasswords(text, parsed)
    if (socketIoNeedRemaining().isEmpty()) {
        finishSocketIo("complete")
        return
    }

    if (timedOut) {
        if (step == "open") {
            finishSocketIo("handshake timeout")
            return
        }
        log.info "Socket.IO ${step} timed out; continuing"
        if (step == "poll" || step == "pong") {
            runIn(1, "socketIoPoll", [overwrite: true])
            return
        }
        if (step?.endsWith("Post")) {
            socketIoHandleStep(step, "", null)
            return
        }
        socketIoSend("GET", null, step)
        return
    }

    if (step != "open" && extractEngineIoOpenPacket(text)) {
        log.warn "Socket.IO server started a new handshake during ${step}"
        socketIoRetryAuth("session reset")
        return
    }

    def msgs = splitEngineIoMessages(text)
    def hasPing = msgs.any { stripEngineIoLengthPrefix(it) == "2" }
    if (hasPing && step != "pong") {
        sio = sioState()
        sio.resumeStep = step
        sio.resumeText = text
        sioStateSet(sio)
        socketIoSend("POST", "3", "pong")
        return
    }

    if (step == "pong") {
        sio = sioState()
        step = (sio.resumeStep ?: "poll") as String
        text = (sio.resumeText ?: text) as String
        sio.remove("resumeStep")
        sio.remove("resumeText")
        sioStateSet(sio)
    }

    socketIoHandleStep(step, text, parsed)
}

def socketIoRetryAuth(String reason) {
    def sio = sioState()
    if (sio.retryOnce != true && ensureCloudAuthForUi()) {
        sio.active = false
        sioStateSet(sio)
        startSocketIoPasswordFetch(true, true)
        return
    }
    finishSocketIo(reason)
}

def socketIoHandleStep(String step, String text, parsed) {
    def sio = sioState()
    if (sio.active != true) return
    if (step == "open") {
        def sid = extractSocketIoSid(text, parsed)
        if (!sid) {
            finishSocketIo("handshake missing sid")
            return
        }
        sio.sid = sid
        sioStateSet(sio)
        log.info "Socket.IO handshake ok"
        updateCredFetchMessage("Connected. Subscribing for device keys…")
        socketIoSend("POST", "40", "nsPost")
        return
    }
    if (step == "nsPost") {
        socketIoSend("GET", null, "nsWait")
        return
    }
    if (step == "nsWait") {
        if (engineIoHasConnectError(text)) {
            log.warn "Socket.IO namespace rejected"
            socketIoRetryAuth("namespace rejected")
            return
        }
        if (!engineIoHasConnectAck(text) && ((sio.nsWaitTries ?: 0) as int) < 6) {
            sio.nsWaitTries = ((sio.nsWaitTries ?: 0) as int) + 1
            sioStateSet(sio)
            socketIoSend("GET", null, "nsWait")
            return
        }
        def userId = jwtUserId() ?: fetchKumoUserIdFromAccount()
        if (userId) {
            log.info "Socket.IO account-level subscribe idLen=${userId.toString().length()}"
            socketIoSend("POST", socketIoEvent(["subscribe", "", userId.toString()]), "acctPost")
        } else {
            log.warn "No Comfort Cloud user id — device keys may not arrive"
            socketIoPostDeviceSubscribes()
        }
        return
    }
    if (step == "acctPost") {
        socketIoSend("GET", null, "acctWait")
        return
    }
    if (step == "acctWait") {
        socketIoPostDeviceSubscribes()
        return
    }
    if (step == "devPost") {
        socketIoSend("GET", null, "devWait")
        return
    }
    if (step == "devWait") {
        socketIoPostForceAndStatus()
        return
    }
    if (step == "forcePost") {
        def serials = (sio.need ?: []) as List
        def statuses = [socketIoEvent(["device_status_v2", ""])]
        serials.each { s -> statuses << socketIoEvent(["device_status_v2", s.toString()]) }
        socketIoSend("POST", statuses.join("\u001e").toString(), "statusPost")
        return
    }
    if (step == "statusPost" || step == "pong" || step == "poll") {
        if (now() > ((sio.deadline ?: 0L) as long)) {
            finishSocketIo("timeout")
            return
        }
        if (step == "statusPost") {
            updateCredFetchMessage("Waiting for device keys from Comfort Cloud…")
            socketIoSend("GET", null, "poll")
            return
        }
        runIn(1, "socketIoPoll", [overwrite: true])
        return
    }
    log.warn "Socket.IO unknown step ${step}"
    finishSocketIo("unknown step")
}

def socketIoPostDeviceSubscribes() {
    def serials = (sioState().need ?: []) as List
    if (!serials) {
        finishSocketIo("complete")
        return
    }
    def subs = serials.collect { s -> socketIoEvent(["subscribe", s.toString()]) }.join("\u001e")
    socketIoSend("POST", subs.toString(), "devPost")
}

def socketIoPostForceAndStatus() {
    def serials = (sioState().need ?: []) as List
    def forces = serials.collect { s -> socketIoEvent(["force_adapter_request", s.toString(), "adapterStatus"]) }.join("\u001e")
    socketIoSend("POST", forces.toString(), "forcePost")
}

def socketIoPoll() {
    def sio = sioState()
    if (sio.active != true) return
    if (now() > ((sio.deadline ?: 0L) as long)) {
        finishSocketIo("timeout")
        return
    }
    socketIoSend("GET", null, "poll")
}

def socketIoEvent(List args) {
    return "42" + JsonOutput.toJson(args)
}

def socketIoWatchdog() {
    def sio = sioState()
    if (sio.active != true) return
    if (socketIoNeedRemaining().isEmpty()) {
        finishSocketIo("complete")
        return
    }
    if (now() > ((sio.deadline ?: 0L) as long)) {
        finishSocketIo("timeout")
        return
    }
    runIn(30, "socketIoWatchdog", [overwrite: true])
}

def extractSocketIoSid(String text, parsed = null) {
    if (parsed instanceof Map && parsed.sid) return parsed.sid as String
    def jsonText = extractEngineIoOpenPacket(text)
    if (jsonText) {
        try {
            def sid = new JsonSlurper().parseText(jsonText)?.sid as String
            if (sid) return sid
        } catch (ignored) {}
    }
    try {
        def json = new JsonSlurper().parseText(text)
        if (json instanceof Map && json.sid) return json.sid as String
    } catch (ignored) {}
    return null
}

def extractEngineIoOpenPacket(String text) {
    if (!text) return null
    def found = null
    splitEngineIoMessages(text).each { raw ->
        if (found) return
        def msg = stripEngineIoLengthPrefix(raw)
        if (msg?.startsWith("0") && msg.length() > 1 && !msg.startsWith("40")) {
            found = msg.substring(1)
        }
    }
    return found
}

def engineIoHasConnectError(String text) {
    return splitEngineIoMessages(text).any { stripEngineIoLengthPrefix(it)?.startsWith("44") }
}

def engineIoHasConnectAck(String text) {
    return splitEngineIoMessages(text).any { msg ->
        def m = stripEngineIoLengthPrefix(msg)
        m == "40" || m?.startsWith("40{") || m?.startsWith("40,")
    }
}

def splitEngineIoMessages(String raw) {
    if (!raw) return []
    return raw.contains("\u001e") ? raw.split("\u001e") as List : [raw]
}

def stripEngineIoLengthPrefix(String msg) {
    if (!msg) return msg
    if (msg.contains(":") && msg.split(":")[0].isNumber()) {
        return msg.split(":", 2)[1]
    }
    return msg
}

def extractSocketIoPasswords(String raw, parsed = null) {
    harvestPasswordNode(parsed)
    if (!raw) return
    splitEngineIoMessages(raw).each { packet ->
        def msg = stripEngineIoLengthPrefix(packet)
        if (!msg) return
        def jsonText = msg
        if (msg.startsWith("42")) jsonText = msg.substring(2)
        else if (!(msg.startsWith("[") || msg.startsWith("{"))) return
        try {
            harvestPasswordNode(new JsonSlurper().parseText(jsonText))
        } catch (Exception e) {
            logDebug "Socket.IO packet parse skipped: ${e.message}"
        }
    }
}

def harvestPasswordNode(node) {
    if (node == null) return
    if (node instanceof List) {
        if (node.size() >= 2) {
            def eventName = node[0]
            if (eventName instanceof String && eventName != "adapter_update") {
                logDebug "Socket.IO event ${eventName}"
            }
            def info = node[1]
            def extra = (node.size() > 2) ? node[2] : null
            if (info instanceof Map) {
                harvestPasswordMap(info, eventName as String)
            } else if (extra instanceof Map) {
                harvestPasswordMap(extra, eventName as String)
            }
        }
        node.each { harvestPasswordNode(it) }
        return
    }
    if (node instanceof Map) harvestPasswordMap(node, null)
}

def harvestPasswordMap(Map info, String eventName) {
    def pw = info.password as String
    def s = (info.deviceSerial ?: info.serial) as String
    if (eventName == "adapter_update") {
        log.info "Socket.IO adapter_update serial=${s ? tailSerial(s) : 'none'} hasKey=${pw ? 'yes' : 'no'}"
    }
    if (s && pw) {
        socketIoSavePassword(s, pw)
        return
    }
    info.each { k, v ->
        if (v instanceof Map || v instanceof List) harvestPasswordNode(v)
    }
}

def socketIoSavePassword(String serial, String password) {
    if (!serial || !password) return
    def sio = sioState()
    def got = [:] + ((sio.got instanceof Map) ? (sio.got as Map) : [:])
    if (got[serial] == password) return
    got[serial] = password
    sio.got = got
    sioStateSet(sio)
    ensureLocalCredEntry(serial)
    state.localCreds[serial].password = password
    updateCredFetchFound()
    log.info "Received local device key for ${tailSerial(serial)}"
}

def socketIoNeedRemaining() {
    def sio = sioState()
    def need = (sio.need ?: state.socketIoNeed ?: []) as List
    def got = (sio.got instanceof Map) ? (sio.got as Map) : ((state.socketIoPasswords instanceof Map) ? (state.socketIoPasswords as Map) : [:])
    return need.findAll { s ->
        def serial = s as String
        !got[serial] && !got.find { k, v -> k?.toString()?.equalsIgnoreCase(serial) }
    }
}

def commitFetchedDeviceKeys() {
    def got = sioState().got
    if (!(got instanceof Map)) return
    got.each { serial, password ->
        if (!serial || !password) return
        ensureLocalCredEntry(serial as String)
        state.localCreds[serial].password = password
    }
    recomputeOfflineReady()
}

def updateCredFetchMessage(String message) {
    def fetch = credFetchMap()
    if (fetch.status != "running") return
    fetch.message = message
    persistCredFetch(fetch)
}

def captureKumoUserId(json) {
    if (!(json instanceof Map)) return
    def id = json.id ?: json.userId
    if (id) state.kumoUserId = id.toString()
}

def jwtUserId() {
    if (state.kumoUserId) return state.kumoUserId as String
    try {
        def token = currentAccessToken()
        if (!token) return null
        def parts = token.split("\\.")
        if (parts.size() < 2) return null
        def payloadB64 = (parts[1] as String).replace("-", "+").replace("_", "/")
        def pad = payloadB64.length() % 4
        if (pad > 0) payloadB64 += "=" * (4 - pad)
        def json = new JsonSlurper().parseText(new String(payloadB64.decodeBase64(), "UTF-8"))
        def id = json?.id ?: json?.sub ?: json?.userId
        if (id) {
            state.kumoUserId = id.toString()
            return state.kumoUserId as String
        }
    } catch (Exception e) {
        logDebug "JWT user id decode failed: ${e.message}"
    }
    return null
}

def fetchKumoUserIdFromAccount() {
    try {
        def resp = null
        httpGet([
            uri: "${API_BASE}/${API_VERSION}/accounts/me",
            headers: authHeaders(currentAccessToken()),
            contentType: "application/json",
            timeout: 15
        ]) { response -> resp = response }
        def json = parseMaybeJson(resp?.data)
        captureKumoUserId(json)
        return state.kumoUserId as String
    } catch (Exception e) {
        logDebug "accounts/me user id fetch failed: ${e.message}"
        return null
    }
}

def tryLegacyPasswordFetch() {
    if (!settings.username || !settings.password) return
    log.info "Trying legacy Comfort Cloud login for local device keys"
    def data = legacyLoginJson(false)
    if (data == null) data = legacyLoginJson(true)
    if (data == null) return
    def found = [:]
    walkLegacyCreds(data, found)
    log.info "Legacy login walked ${found.size()} credential entr${found.size() == 1 ? 'y' : 'ies'}"
    def applied = 0
    def serials = (sioState().need ?: state.socketIoNeed ?: state.knownSerials ?: []) as List
    serials.each { serial ->
        def s = serial as String
        def cred = found[s]
        if (!(cred instanceof Map)) {
            def match = found.find { k, v -> k?.toString()?.equalsIgnoreCase(s) }
            cred = match?.value
        }
        if (!(cred instanceof Map) || !cred.password) return
        socketIoSavePassword(s, cred.password as String)
        if (cred.cryptoSerial) {
            ensureLocalCredEntry(s)
            state.localCreds[s].cryptoSerial = cred.cryptoSerial
        }
        applied++
    }
    if (applied) {
        log.info "Legacy Comfort Cloud login provided ${applied} local device key(s)"
    } else if (found) {
        log.warn "Legacy login returned ${found.size()} key(s) but none matched known zone serials"
    }
}

def legacyLoginJson(Boolean withAppKey) {
    try {
        def headers = [
            "Accept": "application/json, text/plain, */*",
            "Content-Type": "application/json"
        ]
        if (withAppKey) headers["Application-Key"] = LEGACY_APP_KEY
        def loginResp = null
        httpPost([
            uri: "${LEGACY_API_BASE}/login",
            headers: headers,
            body: JsonOutput.toJson([
                username: settings.username,
                password: settings.password,
                appVersion: LEGACY_APP_VERSION
            ]),
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: 20
        ]) { response -> loginResp = response }
        def status = loginResp ? (loginResp.status as int) : 0
        if (status != 200) {
            log.warn "Legacy credential fetch${withAppKey ? ' (app key)' : ''}: HTTP ${status}"
            return null
        }
        return parseMaybeJson(loginResp.data)
    } catch (Exception e) {
        log.warn "Legacy credential fetch${withAppKey ? ' (app key)' : ''} failed: ${e.message}"
        return null
    }
}

def walkLegacyCreds(node, Map found) {
    if (node instanceof List) {
        node.each { walkLegacyCreds(it, found) }
        return
    }
    if (!(node instanceof Map)) return
    node.each { key, value ->
        if (value instanceof Map) {
            def pw = value.password
            if ((pw instanceof String) && pw && (value.cryptoSerial || (key as String).length() >= 8)) {
                found[key as String] = [
                    password: pw as String,
                    cryptoSerial: (value.cryptoSerial ?: "") as String
                ]
            }
            walkLegacyCreds(value, found)
        } else if (value instanceof List) {
            walkLegacyCreds(value, found)
        }
    }
}

def finishSocketIo(String reason) {
    unschedule("socketIoWatchdog")
    unschedule("socketIoPoll")
    def sio = sioState()
    sio.active = false
    sioStateSet(sio)
    commitFetchedDeviceKeys()
    if (reason != "complete" && reason != "cancelled") {
        tryLegacyPasswordFetch()
        if (socketIoNeedRemaining().isEmpty() && (sio.need ?: state.socketIoNeed)) {
            reason = "complete"
        }
    }
    commitFetchedDeviceKeys()
    runIn(1, "commitFetchedDeviceKeys", [overwrite: true])
    log.info "Socket.IO password fetch finished: ${reason}"
    finalizeCredFetch(reason)
    recomputeOfflineReady()
    if (settings.enableSubnetScan) startIpDiscovery()
}

// --- IP discovery ---

def hubLocalIp() {
    def ip = null
    try { ip = location?.hub?.localIP as String } catch (Exception ignored) { }
    if (!ip) {
        try { ip = location?.hubs[0]?.localIP as String } catch (Exception ignored) { }
    }
    if (!ip) {
        try { ip = getHub()?.localIP as String } catch (Exception ignored) { }
    }
    if (!ip) {
        try { ip = location?.hub?.getDataValue("localIP") as String } catch (Exception ignored) { }
    }
    if (ip) {
        ip = ip.toString().trim()
        if (ip.contains(".")) return ip
    }
    return null
}

def hubSubnetPrefix() {
    return subnetPrefixFromIp(hubLocalIp())
}

def subnetPrefixFromIp(String ip) {
    if (!ip) return null
    def cleaned = ip.replaceAll("/.*", "").trim()
    def parts = cleaned.split("\\.")
    if (parts.size() < 3) return null
    return "${parts[0]}.${parts[1]}.${parts[2]}"
}

def resolvedSubnetPrefix() {
    def fromSetting = subnetPrefixFromIp(settings.subnetOverride?.toString())
    return fromSetting ?: hubSubnetPrefix()
}

def ensureSubnetSetting() {
    if (settings.subnetOverride?.toString()?.trim()) return
    def prefix = hubSubnetPrefix()
    if (!prefix) return
    app.updateSetting("subnetOverride", [type: "text", value: prefix])
}

def backfillIpSettingsFromState() {
    if (!(state.zoneIndex instanceof Map)) return
    state.zoneIndex.each { serial, info ->
        def s = serial as String
        def key = unitIpSettingKey(s)
        def settingIp = settings[key]?.toString()?.trim()
        def stateIp = state.localCreds?.get(s)?.address?.toString()?.trim()
        if (!settingIp && stateIp) {
            app.updateSetting(key, [type: "text", value: stateIp])
        }
    }
}

def applyDiscoveredIp(String serial, String ip) {
    if (!serial || !ip) return
    ensureLocalCredEntry(serial)
    state.localCreds[serial].address = ip
    app.updateSetting(unitIpSettingKey(serial), [type: "text", value: ip])
    recomputeOfflineReady()
}

def zoneDisplayName(String serial) {
    if (!serial) return "?"
    def info = state.zoneIndex?.get(serial)
    return (info?.zoneName ?: serial) as String
}

def resolvedUnitIp(String serial) {
    def fromSetting = settings[unitIpSettingKey(serial)]?.toString()?.trim()
    if (fromSetting) return fromSetting
    return state.localCreds?.get(serial)?.address?.toString()?.trim()
}

def unitIpStatusLine(String serial) {
    def ip = resolvedUnitIp(serial)
    if (ip) return "IP: Found ${ip}"
    return "IP: Not set — type it above, or scan below"
}

def unitHasDeviceKey(String serial) {
    if (state.localCreds?.get(serial)?.password) return true
    def got = sioState().got
    return got instanceof Map && got[serial]
}

def unitDeviceKeyStatusLine(String serial) {
    if (unitHasDeviceKey(serial)) return "Device key: Cached"
    if (credFetchRunning()) return "Device key: Fetching…"
    return "Device key: Not yet available — tap Fetch local device keys above"
}

def allDeviceKeysFound() {
    def serials = state.knownSerials ?: []
    if (!(serials instanceof List) || serials.isEmpty()) return false
    return serials.every { unitHasDeviceKey(it as String) }
}

def persistCredFetch(Map fetch) {
    atomicState.credFetch = fetch
    state.credFetch = fetch
}

def credFetchMap() {
    def a = atomicState.credFetch
    if (a instanceof Map && a.status) return [:] + (a as Map)
    return (state.credFetch instanceof Map) ? ([:] + (state.credFetch as Map)) : [:]
}

def credFetchRunning() {
    return credFetchMap().status == "running"
}

def credFetchSnapshotText() {
    def fetch = credFetchMap()
    if (!fetch.status || fetch.status == "idle") return ""
    return (fetch.message ?: "") as String
}

def credFetchHrefDescription() {
    def fetch = credFetchMap()
    if (fetch.status == "running") {
        return "Fetching ${fetch.found ?: 0} of ${fetch.total ?: 0} — tap to watch"
    }
    if (fetch.message) return fetch.message as String
    return "Shows whether each zone's local device key has been fetched"
}

def setCredFetchBlocked(String message) {
    persistCredFetch([
        status: "blocked",
        message: message,
        total: 0,
        found: 0
    ])
    log.info "Device key fetch not started: ${message}"
}

def updateCredFetchFound() {
    def fetch = credFetchMap()
    if (fetch.status != "running") return
    def got = sioState().got
    fetch.found = (got instanceof Map) ? got.size() : 0
    fetch.message = "Fetching… ${fetch.found} of ${fetch.total ?: 0} device keys received."
    persistCredFetch(fetch)
}

def finalizeCredFetch(String reason) {
    def fetch = credFetchMap()
    if (fetch.status == "cancelled") return
    def sio = sioState()
    def need = (sio.need ?: state.socketIoNeed ?: []) as List
    def got = (sio.got instanceof Map) ? (sio.got as Map) : ((state.socketIoPasswords instanceof Map) ? (state.socketIoPasswords as Map) : [:])
    def found = need.count { serial ->
        def s = serial as String
        got[s] || got.find { k, v -> k?.toString()?.equalsIgnoreCase(s) }
    } as int
    def total = need.size() as int
    fetch.found = found
    fetch.total = total
    if (reason == "complete" || (total > 0 && found == total)) {
        fetch.status = "complete"
        fetch.message = "${found} of ${total} device keys fetched."
    } else if (reason == "timeout") {
        fetch.status = "timeout"
        def missing = total - found
        fetch.message = "${found} of ${total} device keys fetched from Comfort Cloud. ${missing} still missing — tap Fetch local device keys to try again."
    } else {
        fetch.status = "error"
        fetch.message = "Could not fetch device keys (${reason}). Tap Fetch local device keys to try again."
    }
    persistCredFetch(fetch)
}

def cancelCredFetch() {
    def sio = sioState()
    sio.active = false
    sioStateSet(sio)
    unschedule("socketIoWatchdog")
    unschedule("socketIoPoll")
    def fetch = credFetchMap()
    fetch.status = "cancelled"
    fetch.message = "Fetch cancelled."
    persistCredFetch(fetch)
    log.info "Device key fetch cancelled"
}

def renderCredFetchUnitStatus() {
    def serials = state.knownSerials ?: (state.zoneIndex instanceof Map ? state.zoneIndex.keySet() as List : [])
    if (serials == null || serials.size() == 0) {
        paragraph "No zones discovered yet. Save credentials and a site first."
        return
    }
    serials.each { serial ->
        def s = serial as String
        def name = zoneDisplayName(s)
        if (unitHasDeviceKey(s)) {
            paragraph "${name} — cached"
        } else if (credFetchRunning()) {
            paragraph "${name} — fetching…"
        } else {
            paragraph "${name} — not yet available"
        }
    }
}

def allUnitIpsFound() {
    def serials = state.knownSerials ?: []
    if (!(serials instanceof List) || serials.isEmpty()) return false
    return serials.every { resolvedUnitIp(it as String) }
}

def ipScanMap() {
    return (state.ipScan instanceof Map) ? ([:] + (state.ipScan as Map)) : [:]
}

def ipScanSnapshotText() {
    def scan = ipScanMap()
    if (!scan.status || scan.status == "idle") {
        def known = state.knownSerials
        if (!(known instanceof List) || known.isEmpty()) return ""
        return "Scan has not been run. Type IPs above or tap Find missing unit IPs."
    }
    if (scan.status == "running") {
        def host = (scan.ipIdx ?: 0) as int
        def total = (scan.ipTotal ?: IP_PROBE_HOST_MAX) as int
        return "Scanning ${scan.subnet ?: ''}.x — ${host}/${total}. Open Find unit IPs to watch progress."
    }
    return (scan.message ?: "") as String
}

def ipScanHrefDescription() {
    def scan = ipScanMap()
    if (scan.status == "running") {
        def host = (scan.ipIdx ?: 0) as int
        def total = (scan.ipTotal ?: IP_PROBE_HOST_MAX) as int
        return "Scanning ${host}/${total} — tap to watch"
    }
    if (scan.message) return scan.message as String
    return "Optional: probe the LAN for units without an IP"
}

def ipScanProgressBar() {
    def scan = ipScanMap()
    def serialTotal = Math.max(1, (scan.serialTotal ?: 1) as int)
    def serialIdx = (scan.serialIdx ?: 0) as int
    def ipIdx = (scan.ipIdx ?: 0) as int
    def ipTotal = Math.max(1, (scan.ipTotal ?: IP_PROBE_HOST_MAX) as int)
    def done = serialIdx * ipTotal + ipIdx
    def total = serialTotal * ipTotal
    def pct = Math.min(100, (int)((done * 100) / total))
    def filled = (int)((pct * 20) / 100)
    def bar = ""
    for (int i = 0; i < 20; i++) {
        bar += (i < filled) ? "█" : "░"
    }
    def unitPart = serialTotal > 1 ? "unit ${Math.min(serialIdx + 1, serialTotal)}/${serialTotal}, " : ""
    return "${bar} ${pct}% (${unitPart}host ${ipIdx}/${ipTotal})"
}

def renderIpScanUnitStatus() {
    def scan = ipScanMap()
    def matched = (scan.matched instanceof Map) ? scan.matched : [:]
    def skipped = (scan.skipped instanceof Map) ? scan.skipped : [:]
    def notFound = (scan.notFound instanceof List) ? scan.notFound : []
    def pending = (scan.pending instanceof List) ? scan.pending : []
    def current = scan.currentSerial as String
    def serials = state.knownSerials ?: (state.zoneIndex instanceof Map ? state.zoneIndex.keySet() as List : [])
    if (!serials) {
        paragraph "No zones discovered yet. Save credentials and a site first."
        return
    }
    serials.each { serial ->
        def s = serial as String
        def name = zoneDisplayName(s)
        def ip = matched[s] ?: resolvedUnitIp(s)
        if (ip) {
            paragraph "${name} — found ${ip}"
        } else if (s in notFound) {
            paragraph "${name} — not found. Enter the IP manually."
        } else if (skipped[s]) {
            paragraph "${name} — skipped (${skipped[s]})"
        } else if (scan.status == "running" && s == current) {
            paragraph "${name} — searching ${scan.currentIp ?: ''}…"
        } else if (s in pending || scan.status == "running") {
            paragraph "${name} — waiting"
        } else {
            paragraph "${name} — ${unitIpStatusLine(s)}"
        }
    }
}

def setIpScanBlocked(String message) {
    state.ipScan = [
        status: "blocked",
        message: message,
        subnet: resolvedSubnetPrefix() ?: "",
        currentIp: "",
        ipIdx: 0,
        ipTotal: IP_PROBE_HOST_MAX,
        serialIdx: 0,
        serialTotal: 0,
        currentSerial: "",
        matched: [:],
        pending: [],
        skipped: [:],
        notFound: []
    ]
    log.info "IP scan not started: ${message}"
}

def recordIpScanMatch(String serial, String ip) {
    def scan = ipScanMap()
    def matched = (scan.matched instanceof Map) ? ([:] + (scan.matched as Map)) : [:]
    matched[serial] = ip
    scan.matched = matched
    scan.pending = ((scan.pending instanceof List) ? (scan.pending as List) : []).findAll { it != serial }
    scan.message = "Matched ${zoneDisplayName(serial)} at ${ip}"
    state.ipScan = scan
}

def recordIpScanNotFound(String serial) {
    def scan = ipScanMap()
    def notFound = ((scan.notFound instanceof List) ? (scan.notFound as List) : []) + [serial]
    scan.notFound = notFound
    scan.pending = ((scan.pending instanceof List) ? (scan.pending as List) : []).findAll { it != serial }
    scan.message = "No match for ${zoneDisplayName(serial)} on ${scan.subnet ?: ''}.x"
    state.ipScan = scan
}

def updateIpScanProgress(String serial, String ip, int host) {
    def scan = ipScanMap()
    scan.currentIp = ip
    scan.ipIdx = host
    scan.currentSerial = serial
    scan.serialIdx = (state.ipProbeSerialIdx ?: 0) as int
    scan.message = "Probing ${ip} for ${zoneDisplayName(serial)}"
    state.ipScan = scan
}

def assignedIps() {
    def ips = [] as Set
    (state.localCreds ?: [:]).each { serial, creds ->
        if (creds instanceof Map && creds.address) {
            ips << creds.address.toString()
        }
    }
    def hubIp = hubLocalIp()
    if (hubIp) ips << hubIp.toString()
    return ips
}

def startIpDiscovery() {
    if (state.ipScan?.status == "running") {
        log.info "IP scan already running"
        return
    }

    def subnet = resolvedSubnetPrefix()
    if (!subnet) {
        setIpScanBlocked("Could not determine subnet. Enter the first three octets (e.g. 192.168.1) and try again.")
        return
    }
    ensureSubnetSetting()

    def known = (state.knownSerials ?: []) as List
    if (known.isEmpty()) {
        setIpScanBlocked("No zones discovered yet. Save credentials and a site first.")
        return
    }

    def skipped = [:]
    def serials = []
    def alreadyHaveIp = 0
    known.each { serial ->
        def s = serial as String
        def creds = state.localCreds[s]
        if (creds instanceof Map && creds.address) {
            alreadyHaveIp++
            return
        }
        if (!(creds instanceof Map) || !creds.password) {
            skipped[s] = "no local device key yet"
            return
        }
        if (!creds.cryptoSerial) {
            skipped[s] = "waiting for unit status from cloud"
            return
        }
        serials << s
    }

    if (serials.isEmpty()) {
        def parts = []
        if (alreadyHaveIp) parts << "${alreadyHaveIp} already have an IP"
        if (!skipped.isEmpty()) parts << "${skipped.size()} zones don't have a local device key yet — tap Fetch local device keys above, then try scanning again"
        def msg = parts.isEmpty() ? "No units to scan." : "Nothing to scan: ${parts.join('; ')}."
        setIpScanBlocked(msg)
        return
    }

    state.ipScanGen = ((state.ipScanGen ?: 0) as int) + 1
    state.ipProbeSeq = 0
    state.ipProbeHandledSeq = -1
    state.ipProbeSerials = serials
    state.ipProbeSerialIdx = 0
    state.ipProbeHost = 1
    state.ipProbeSubnet = subnet
    state.remove("ipProbeCandidates")
    state.remove("ipProbeIpIdx")

    state.ipScan = [
        status: "running",
        message: "Scanning ${subnet}.x for ${serials.size()} unit(s)…",
        subnet: subnet,
        currentIp: "",
        ipIdx: 0,
        ipTotal: IP_PROBE_HOST_MAX,
        serialIdx: 0,
        serialTotal: serials.size(),
        currentSerial: serials[0],
        matched: [:],
        pending: serials.collect { it },
        skipped: skipped,
        notFound: []
    ]
    log.info "IP scan started on ${subnet}.x for ${serials.size()} unit(s)"
    scheduleNextIpProbe()
}

def cancelIpDiscovery() {
    state.ipScanGen = ((state.ipScanGen ?: 0) as int) + 1
    unschedule("ipProbeTimeout")
    unschedule("scheduleNextIpProbe")
    clearIpProbeRuntime()
    def scan = ipScanMap()
    scan.status = "cancelled"
    scan.message = "Scan cancelled."
    scan.currentIp = ""
    state.ipScan = scan
    log.info "IP scan cancelled"
}

def clearIpProbeRuntime() {
    state.remove("ipProbeSerials")
    state.remove("ipProbeCandidates")
    state.remove("ipProbeSerialIdx")
    state.remove("ipProbeIpIdx")
    state.remove("ipProbeHost")
    state.remove("ipProbeSubnet")
}

def probeEventIsCurrent(Map data) {
    def gen = (data?.scanGen ?: 0) as int
    def seq = (data?.probeSeq ?: 0) as int
    if (gen != ((state.ipScanGen ?: 0) as int)) return false
    if (state.ipScan?.status != "running") return false
    if (((state.ipProbeHandledSeq ?: -1) as int) >= seq) return false
    state.ipProbeHandledSeq = seq
    return true
}

def handleLocalProbeResponse(String serial, parsed, Map data) {
    if (parsed) {
        applyDiscoveredIp(serial, data.probeIp as String)
        log.info "Matched ${tailSerial(serial)} to IP ${data.probeIp}"
    }
    if (!probeEventIsCurrent(data)) return
    unschedule("ipProbeTimeout")
    if (parsed) {
        recordIpScanMatch(serial, data.probeIp as String)
        state.ipProbeSerialIdx = ((state.ipProbeSerialIdx ?: 0) as int) + 1
        state.ipProbeHost = 1
    }
    runIn(1, "scheduleNextIpProbe", [overwrite: true])
}

def ipProbeTimeout(data) {
    if (!probeEventIsCurrent(data instanceof Map ? data : [:])) return
    def serial = data?.serial as String
    if (serial) {
        state.ipProbeActive?.remove(serial)
        state.localInFlight[serial] = false
    }
    runIn(1, "scheduleNextIpProbe", [overwrite: true])
}

def finishIpDiscovery() {
    unschedule("ipProbeTimeout")
    unschedule("scheduleNextIpProbe")
    clearIpProbeRuntime()
    def scan = ipScanMap()
    def matched = (scan.matched instanceof Map) ? scan.matched.size() : 0
    def notFound = (scan.notFound instanceof List) ? scan.notFound.size() : 0
    scan.status = "complete"
    scan.currentIp = ""
    scan.message = "Scan complete: ${matched} found, ${notFound} not found."
    if (notFound) {
        scan.message = "${scan.message} Enter remaining IPs manually."
    }
    state.ipScan = scan
    log.info scan.message
}

def scheduleNextIpProbe() {
    if (state.ipScan?.status != "running") return

    def serials = state.ipProbeSerials ?: []
    def sIdx = (state.ipProbeSerialIdx ?: 0) as int
    def subnet = state.ipProbeSubnet as String

    if (!serials || sIdx >= serials.size() || !subnet) {
        finishIpDiscovery()
        return
    }

    def serial = serials[sIdx] as String
    if (state.localCreds[serial]?.address) {
        recordIpScanMatch(serial, state.localCreds[serial].address as String)
        state.ipProbeSerialIdx = sIdx + 1
        state.ipProbeHost = 1
        runIn(1, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    def host = (state.ipProbeHost ?: 1) as int
    def skip = assignedIps()
    while (host <= IP_PROBE_HOST_MAX) {
        def candidate = "${subnet}.${host}".toString()
        if (!(candidate in skip)) break
        host++
    }

    if (host > IP_PROBE_HOST_MAX) {
        recordIpScanNotFound(serial)
        state.ipProbeSerialIdx = sIdx + 1
        state.ipProbeHost = 1
        runIn(1, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    if (state.localInFlight[serial]) {
        runIn(2, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    def ip = "${subnet}.${host}".toString()
    state.ipProbeHost = host + 1
    state.ipProbeActive[serial] = true
    state.ipProbeSeq = ((state.ipProbeSeq ?: 0) as int) + 1
    def seq = state.ipProbeSeq as int
    def gen = (state.ipScanGen ?: 0) as int
    updateIpScanProgress(serial, ip, host)

    def sent = localPut(serial, LOCAL_STATUS_QUERY, [
        requestType: "localProbe",
        probeIp: ip,
        probeSerial: serial,
        overrideIp: ip,
        scanGen: gen,
        probeSeq: seq
    ])
    if (!sent) {
        state.ipProbeActive?.remove(serial)
        runIn(1, "scheduleNextIpProbe", [overwrite: true])
        return
    }

    runIn(LOCAL_HTTP_TIMEOUT + 4, "ipProbeTimeout", [
        overwrite: true,
        data: [scanGen: gen, probeSeq: seq, serial: serial]
    ])
}

def logDebug(msg) {
    if (settings.debugLogging) log.info msg
}
