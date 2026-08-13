/**
 * Mitsubishi Comfort Cloud — indoor unit temperature/humidity component driver.
 * Nested under the zone thermostat parent device.
 */
metadata {
    definition(name: "Mitsubishi Comfort Indoor Sensor", namespace: "ephrayim", author: "ephrayim", importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat", component: true) {
        capability "Refresh"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"

        attribute "cloudStatus", "STRING"
        attribute "tempSource", "STRING"
        attribute "activeThermistor", "STRING"
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
    ["filterDirty", "defrost", "standby"].each { name ->
        if (device.currentValue(name) != null) {
            device.deleteCurrentState(name)
        }
    }
    if (!device.currentValue("cloudStatus")) {
        sendEvent(name: "cloudStatus", value: "online")
    }
}

def refresh() {
    parent?.componentRefresh(device)
}

void parse(List<Map> events) {
    events?.each { ev ->
        if (ev instanceof Map && ev.name != null && ev.containsKey("value") && ev.value != null) {
            sendEvent(ev)
        }
    }
}

def applyIndoorState(Map st) {
    if (!st) return
    def unit = (st.tempUnit ?: tempUnit()) as String
    if (st.temperature != null) {
        sendEvent(name: "temperature", value: st.temperature, unit: unit)
    }
    if (st.humidity != null) {
        sendEvent(name: "humidity", value: st.humidity, unit: "%")
    }
    if (st.cloudStatus != null) {
        sendEvent(name: "cloudStatus", value: st.cloudStatus)
    }
    if (st.tempSource != null) {
        sendEvent(name: "tempSource", value: st.tempSource.toString())
    }
    if (st.activeThermistor != null) {
        sendEvent(name: "activeThermistor", value: st.activeThermistor.toString())
    }
}

def tempUnit() {
    return location?.temperatureScale == "C" ? "°C" : "°F"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
