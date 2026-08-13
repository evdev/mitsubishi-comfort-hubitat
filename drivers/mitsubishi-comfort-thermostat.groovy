/**
 * Mitsubishi Comfort Cloud — zone thermostat parent driver.
 * Parent app owns all cloud/LAN communication; this driver exposes Hubitat
 * thermostat capabilities and creates nested indoor/filter/diagnostics/wireless
 * component devices.
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
        attribute "defrost", "STRING"
        attribute "standby", "STRING"
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

def componentRefresh(child) {
    parent?.componentRefresh(child)
}

// --- Nested component devices ---

def ensureComponents(Map opts) {
    def serial = opts?.serial as String
    def zoneId = opts?.zoneId as String
    def zoneName = (opts?.zoneName ?: "Zone") as String
    def hasSensor = opts?.hasSensor == true
    if (!serial || !zoneId) return
    ensureComponent("mc-${serial}-indoor", "Mitsubishi Comfort Indoor Sensor", "${zoneName} Indoor", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "indoor"
    ])
    ensureComponent("mc-${serial}-diag", "Mitsubishi Comfort Diagnostics", "${zoneName} Diagnostics", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "diag"
    ])
    ensureComponent("mc-${zoneId}-filter", "Mitsubishi Comfort Filter Reminder", "${zoneName} Filter", [
        deviceSerial: serial, zoneId: zoneId, deviceType: "filter"
    ])
    if (hasSensor) {
        ensureComponent("mc-${serial}-wireless", "Mitsubishi Comfort Wireless Sensor", "${zoneName} Wireless", [
            deviceSerial: serial, zoneId: zoneId, deviceType: "wireless"
        ])
    }
}

def ensureComponent(String dni, String driverName, String label, Map dataValues) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("ephrayim", driverName, dni, [label: label, name: label, isComponent: true])
            log.info "Created component ${label} (${dni})"
        } catch (Exception e) {
            log.error "Failed to create component ${dni}: ${e.message}"
            return null
        }
    } else if (child.label != label && !child.label) {
        child.setLabel(label)
    }
    dataValues.each { k, v ->
        if (v != null) child.updateDataValue(k as String, v.toString())
    }
    if (dataValues.deviceType) {
        updateDataValue("dni_${dataValues.deviceType}", dni)
    }
    return child
}

def componentDni(String type) {
    def serial = device.getDataValue("deviceSerial")
    def zoneId = device.getDataValue("zoneId")
    if (!serial) return null
    switch (type) {
        case "indoor": return "mc-${serial}-indoor"
        case "diag": return "mc-${serial}-diag"
        case "filter": return zoneId ? "mc-${zoneId}-filter" : null
        case "wireless": return "mc-${serial}-wireless"
        default: return null
    }
}

def componentByType(String type) {
    def dni = device.getDataValue("dni_${type}") ?: componentDni(type)
    def kids = getChildDevices() ?: []
    def child = dni ? kids.find { (it.deviceNetworkId as String) == dni } : null
    if (!child && dni) {
        try { child = getChildDevice(dni) } catch (ignored) {}
    }
    if (!child) {
        child = kids.find { it.getDataValue("deviceType") == type }
    }
    return child
}

def emitChildEvents(String type, List events) {
    def payload = events?.findAll { it instanceof Map && it.name != null && it.containsKey("value") && it.value != null } ?: []
    if (payload.isEmpty()) return
    def child = componentByType(type)
    if (!child) {
        def kids = getChildDevices()?.collect { it.deviceNetworkId } ?: []
        log.warn "Missing ${type} component (dni=${componentDni(type)}, serial=${device.getDataValue('deviceSerial')}, children=${kids})"
        return
    }
    try {
        getChildDevice(child.deviceNetworkId)?.parse(payload)
    } catch (Exception e) {
        log.warn "parse failed for ${type}: ${e.message}"
        payload.each { ev ->
            child.sendEvent(name: ev.name, value: ev.value, unit: ev.unit, descriptionText: ev.descriptionText)
        }
    }
}

def queueComponentState(String type, st) {
    def incoming = coerceMap(st)
    if (!incoming) return
    def cs = (state.componentState instanceof Map) ? [:] + state.componentState : [:]
    def prev = (cs[type] instanceof Map) ? [:] + cs[type] : [:]
    prev.putAll(incoming)
    cs[type] = prev
    state.componentState = cs
}

def coerceMap(value) {
    if (value instanceof Map) return value
    if (value instanceof String) {
        def trimmed = value.trim()
        if (trimmed.startsWith("{")) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(trimmed)
                return (parsed instanceof Map) ? parsed : null
            } catch (ignored) {}
        }
    }
    return null
}

def publishComponents() {
    def data = (state.componentState instanceof Map) ? state.componentState : [:]
    if (data.indoor instanceof Map) emitChildEvents("indoor", indoorEvents(data.indoor))
    if (data.diag instanceof Map) emitChildEvents("diag", diagnosticEvents(data.diag))
    if (data.filter instanceof Map) emitChildEvents("filter", filterEvents(data.filter))
    if (data.wireless instanceof Map) emitChildEvents("wireless", wirelessEvents(data.wireless))
}

def indoorEvents(Map st) {
    def unit = (st.tempUnit ?: temperatureScaleUnit()) as String
    def events = []
    if (st.temperature != null) events << [name: "temperature", value: st.temperature, unit: unit, descriptionText: "temperature is ${st.temperature}${unit}"]
    if (st.humidity != null) events << [name: "humidity", value: st.humidity, unit: "%", descriptionText: "humidity is ${st.humidity}%"]
    if (st.cloudStatus != null) events << [name: "cloudStatus", value: st.cloudStatus, descriptionText: "cloudStatus is ${st.cloudStatus}"]
    if (st.tempSource != null) events << [name: "tempSource", value: st.tempSource.toString(), descriptionText: "tempSource is ${st.tempSource}"]
    if (st.activeThermistor != null) events << [name: "activeThermistor", value: st.activeThermistor.toString(), descriptionText: "activeThermistor is ${st.activeThermistor}"]
    return events
}

def diagnosticEvents(Map st) {
    def events = []
    if (st.rssi != null) events << [name: "rssi", value: st.rssi, descriptionText: "rssi is ${st.rssi}"]
    if (st.firmwareVersion != null) events << [name: "firmwareVersion", value: st.firmwareVersion.toString(), descriptionText: "firmwareVersion is ${st.firmwareVersion}"]
    if (st.routerSsid != null) events << [name: "routerSsid", value: st.routerSsid.toString(), descriptionText: "routerSsid is ${st.routerSsid}"]
    if (st.cloudStatus != null) events << [name: "cloudStatus", value: st.cloudStatus, descriptionText: "cloudStatus is ${st.cloudStatus}"]
    return events
}

def filterEvents(Map st) {
    def events = []
    if (st.filterDirty != null) events << [name: "filterDirty", value: st.filterDirty.toString(), descriptionText: "filterDirty is ${st.filterDirty}"]
    if (st.lastFilterReminder != null) events << [name: "lastFilterReminder", value: st.lastFilterReminder.toString(), descriptionText: "lastFilterReminder is ${st.lastFilterReminder}"]
    if (st.reminderIntervalDays != null) events << [name: "reminderIntervalDays", value: st.reminderIntervalDays as BigDecimal, descriptionText: "reminderIntervalDays is ${st.reminderIntervalDays}"]
    if (st.remindersEnabled != null) events << [name: "remindersEnabled", value: st.remindersEnabled.toString(), descriptionText: "remindersEnabled is ${st.remindersEnabled}"]
    if (st.cloudStatus != null) events << [name: "cloudStatus", value: st.cloudStatus, descriptionText: "cloudStatus is ${st.cloudStatus}"]
    return events
}

def wirelessEvents(Map st) {
    def unit = (st.tempUnit ?: temperatureScaleUnit()) as String
    def events = []
    if (st.temperature != null) events << [name: "temperature", value: st.temperature, unit: unit, descriptionText: "temperature is ${st.temperature}${unit}"]
    if (st.humidity != null) events << [name: "humidity", value: st.humidity, unit: "%", descriptionText: "humidity is ${st.humidity}%"]
    if (st.battery != null) events << [name: "battery", value: st.battery, unit: "%", descriptionText: "battery is ${st.battery}%"]
    if (st.rssi != null) events << [name: "rssi", value: st.rssi, descriptionText: "rssi is ${st.rssi}"]
    if (st.cloudStatus != null) events << [name: "cloudStatus", value: st.cloudStatus, descriptionText: "cloudStatus is ${st.cloudStatus}"]
    return events
}

def scheduleComponentPublish() {
    publishComponents()
    runIn(1, "publishComponents", [overwrite: true])
}

def forwardIndoorState(st) {
    queueComponentState("indoor", st)
    scheduleComponentPublish()
}

def forwardDiagnosticState(st) {
    queueComponentState("diag", st)
    scheduleComponentPublish()
}

def forwardFilterState(st) {
    queueComponentState("filter", st)
    scheduleComponentPublish()
}

def forwardWirelessState(st) {
    queueComponentState("wireless", st)
    scheduleComponentPublish()
}

def pushCloudStatus(String cloudStatus) {
    if (!cloudStatus) return
    sendEvent(name: "cloudStatus", value: cloudStatus)
    ["indoor", "diag", "filter", "wireless"].each { type ->
        queueComponentState(type, [cloudStatus: cloudStatus])
    }
    scheduleComponentPublish()
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
    if (st.defrost != null) {
        sendEvent(name: "defrost", value: st.defrost.toString())
    }
    if (st.standby != null) {
        sendEvent(name: "standby", value: st.standby.toString())
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

    def hadComponents = false
    ["indoor", "diag", "filter", "wireless"].each { type ->
        def nested = coerceMap(st[type])
        if (nested) {
            queueComponentState(type, nested)
            hadComponents = true
        }
    }
    if (st.componentCloudStatus != null) {
        sendEvent(name: "cloudStatus", value: st.componentCloudStatus)
        ["indoor", "diag", "filter", "wireless"].each { type ->
            queueComponentState(type, [cloudStatus: st.componentCloudStatus])
        }
        hadComponents = true
    }
    if (hadComponents) {
        scheduleComponentPublish()
    }
}

def temperatureScaleUnit() {
    return location?.temperatureScale == "C" ? "°C" : "°F"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
