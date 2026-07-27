/**
 * Mitsubishi Comfort Cloud — zone thermostat child driver.
 * Parent app owns all cloud communication; this driver exposes Hubitat capabilities.
 */
metadata {
    definition(name: "Mitsubishi Comfort Thermostat", namespace: "ephrayim", author: "ephrayim", importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat") {
        capability "Actuator"
        capability "Refresh"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Thermostat"

        attribute "supportedThermostatModes", "STRING"
        attribute "supportedThermostatFanModes", "STRING"
        attribute "vanePosition", "STRING"
        attribute "cloudStatus", "STRING"
        attribute "connectionPath", "STRING"
        attribute "comfortFanSpeed", "STRING"
        attribute "comfortMode", "STRING"
        attribute "minHeatingSetpoint", "NUMBER"
        attribute "maxHeatingSetpoint", "NUMBER"
        attribute "minCoolingSetpoint", "NUMBER"
        attribute "maxCoolingSetpoint", "NUMBER"
        attribute "minAutoSetpoint", "NUMBER"
        attribute "maxAutoSetpoint", "NUMBER"
        attribute "minDrySetpoint", "NUMBER"
        attribute "maxDrySetpoint", "NUMBER"

        command "setVanePosition", [[name: "position*", type: "ENUM", constraints: [
            "auto", "swing", "lowest", "low", "middle", "high", "highest"
        ]]]
        command "setComfortFanSpeed", [[name: "fanSpeed*", type: "ENUM", constraints: [
            "auto", "quiet", "low", "medium", "high", "powerful"
        ]]]
        command "setComfortMode", [[name: "mode*", type: "ENUM", constraints: [
            "off", "heat", "cool", "auto", "dry", "fan"
        ]]]
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

// --- Thermostat capability commands ---

def off() {
    setThermostatMode("off")
}

def heat() {
    setThermostatMode("heat")
}

def cool() {
    setThermostatMode("cool")
}

def auto() {
    setThermostatMode("auto")
}

def emergencyHeat() {
    // Not supported by Comfort Cloud mini-splits; map to heat.
    setThermostatMode("heat")
}

def dry() {
    setThermostatMode("dry")
}

def fan() {
    setThermostatMode("fan")
}

def setThermostatMode(mode) {
    if (mode == null) return
    def normalized = mode.toString().toLowerCase()
    logDebug "setThermostatMode(${normalized})"
    parent?.componentSetMode(device, normalized)
}

def setComfortMode(mode) {
    setThermostatMode(mode)
}

def setHeatingSetpoint(temperature) {
    if (temperature == null) return
    logDebug "setHeatingSetpoint(${temperature})"
    parent?.componentSetHeatingSetpoint(device, temperature)
}

def setCoolingSetpoint(temperature) {
    if (temperature == null) return
    logDebug "setCoolingSetpoint(${temperature})"
    parent?.componentSetCoolingSetpoint(device, temperature)
}

def setThermostatFanMode(fanmode) {
    if (fanmode == null) return
    def normalized = fanmode.toString().toLowerCase()
    logDebug "setThermostatFanMode(${normalized})"
    parent?.componentSetFanSpeed(device, normalized)
}

def setComfortFanSpeed(fanmode) {
    setThermostatFanMode(fanmode)
}

def fanAuto() {
    setThermostatFanMode("auto")
}

def fanOn() {
    setThermostatFanMode("high")
}

def fanCirculate() {
    setThermostatFanMode("medium")
}

def setVanePosition(position) {
    if (position == null) return
    def normalized = position.toString().toLowerCase()
    logDebug "setVanePosition(${normalized})"
    parent?.componentSetVanePosition(device, normalized)
}

// --- State pushed from parent app ---

def applyThermostatState(Map st) {
    if (!st) return

    if (st.supportedModes != null) {
        sendEvent(name: "supportedThermostatModes", value: st.supportedModes)
    }
    if (st.supportedFanModes != null) {
        sendEvent(name: "supportedThermostatFanModes", value: st.supportedFanModes)
    }
    def unit = (st.tempUnit ?: temperatureScaleUnit()) as String
    if (st.temperature != null) {
        sendEvent(name: "temperature", value: st.temperature, unit: unit)
    }
    if (st.heatingSetpoint != null) {
        sendEvent(name: "heatingSetpoint", value: st.heatingSetpoint, unit: unit)
    }
    if (st.coolingSetpoint != null) {
        sendEvent(name: "coolingSetpoint", value: st.coolingSetpoint, unit: unit)
    }
    if (st.thermostatSetpoint != null) {
        sendEvent(name: "thermostatSetpoint", value: st.thermostatSetpoint, unit: unit)
    }
    if (st.thermostatMode != null) {
        sendEvent(name: "thermostatMode", value: st.thermostatMode)
        sendEvent(name: "comfortMode", value: st.thermostatMode)
    }
    if (st.thermostatFanMode != null) {
        sendEvent(name: "thermostatFanMode", value: st.thermostatFanMode)
        sendEvent(name: "comfortFanSpeed", value: st.thermostatFanMode)
    }
    if (st.thermostatOperatingState != null) {
        sendEvent(name: "thermostatOperatingState", value: st.thermostatOperatingState)
    }
    if (st.vanePosition != null) {
        sendEvent(name: "vanePosition", value: st.vanePosition)
    }
    if (st.cloudStatus != null) {
        sendEvent(name: "cloudStatus", value: st.cloudStatus)
    }
    if (st.connectionPath != null) {
        sendEvent(name: "connectionPath", value: st.connectionPath)
    }
    if (st.model != null) {
        updateDataValue("model", st.model.toString())
    }
    if (st.serialNumber != null) {
        updateDataValue("serialNumber", st.serialNumber.toString())
    }
    [
        "minHeatingSetpoint", "maxHeatingSetpoint",
        "minCoolingSetpoint", "maxCoolingSetpoint",
        "minAutoSetpoint", "maxAutoSetpoint",
        "minDrySetpoint", "maxDrySetpoint"
    ].each { attr ->
        if (st[attr] != null) {
            sendEvent(name: attr, value: st[attr], unit: unit)
        }
    }
}

def temperatureScaleUnit() {
    return location?.temperatureScale == "C" ? "°C" : "°F"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
