/**
 * Mitsubishi Comfort Cloud — WiFi adapter diagnostics component driver.
 * Nested under the zone thermostat parent device.
 */
metadata {
    definition(name: "Mitsubishi Comfort Diagnostics", namespace: "ephrayim", author: "ephrayim", importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat", component: true) {
        capability "Refresh"
        capability "Sensor"
        capability "Signal Strength"

        attribute "firmwareVersion", "STRING"
        attribute "routerSsid", "STRING"
        attribute "cloudStatus", "STRING"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    if (!device.currentValue("cloudStatus")) {
        sendEvent(name: "cloudStatus", value: "online")
    }
}

def refresh() {
    parent?.componentRefresh(device)
}

def applyDiagnosticState(Map st) {
    if (!st) return
    if (st.rssi != null) {
        sendEvent(name: "rssi", value: st.rssi)
    }
    if (st.firmwareVersion != null) {
        sendEvent(name: "firmwareVersion", value: st.firmwareVersion.toString())
    }
    if (st.routerSsid != null) {
        sendEvent(name: "routerSsid", value: st.routerSsid.toString())
    }
    if (st.cloudStatus != null) {
        sendEvent(name: "cloudStatus", value: st.cloudStatus)
    }
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
