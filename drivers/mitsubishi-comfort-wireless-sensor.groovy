/**
 * Mitsubishi Comfort Cloud — wireless room sensor (PAC-USWHS003-TH-1) component driver.
 * Nested under the zone thermostat parent device.
 */
metadata {
    definition(name: "Mitsubishi Comfort Wireless Sensor", namespace: "ephrayim", author: "ephrayim", importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat", component: true) {
        capability "Refresh"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Battery"
        capability "Signal Strength"

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

void parse(String description) { log.warn "parse(String description) not implemented" }

void parse(List description) {
    description?.each { ev ->
        if (ev instanceof Map && ev.name != null && ev.containsKey("value") && ev.value != null) {
            if (logEnable) log.info "parse ${ev.name}=${ev.value}"
            sendEvent(ev)
        }
    }
}

def applyWirelessState(Map st) {
    if (!st) return
    def unit = (st.tempUnit ?: tempUnit()) as String
    if (st.temperature != null) {
        sendEvent(name: "temperature", value: st.temperature, unit: unit)
    }
    if (st.humidity != null) {
        sendEvent(name: "humidity", value: st.humidity, unit: "%")
    }
    if (st.battery != null) {
        sendEvent(name: "battery", value: st.battery, unit: "%")
    }
    if (st.rssi != null) {
        sendEvent(name: "rssi", value: st.rssi)
    }
    if (st.cloudStatus != null) {
        sendEvent(name: "cloudStatus", value: st.cloudStatus)
    }
}

def tempUnit() {
    return location?.temperatureScale == "C" ? "°C" : "°F"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
