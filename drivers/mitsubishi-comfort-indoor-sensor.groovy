/**
 * Mitsubishi Comfort Cloud — indoor unit temperature/humidity child driver.
 */
metadata {
    definition(name: "Mitsubishi Comfort Indoor Sensor", namespace: "ephrayim", author: "ephrayim", importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat") {
        capability "Refresh"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"

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
}

def tempUnit() {
    return location?.temperatureScale == "C" ? "°C" : "°F"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
