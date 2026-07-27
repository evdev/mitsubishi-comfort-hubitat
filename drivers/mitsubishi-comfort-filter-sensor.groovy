/**
 * Mitsubishi Comfort Cloud — filter reminder metadata child driver.
 * Reports reminder configuration/history only; does not infer filter condition.
 */
metadata {
    definition(name: "Mitsubishi Comfort Filter Reminder", namespace: "ephrayim", author: "ephrayim", importUrl: "https://github.com/evdev/mitsubishi-comfort-hubitat") {
        capability "Refresh"
        capability "Sensor"

        attribute "lastFilterReminder", "STRING"
        attribute "reminderIntervalDays", "NUMBER"
        attribute "remindersEnabled", "STRING"
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

def applyFilterState(Map st) {
    if (!st) return
    if (st.lastFilterReminder != null) {
        sendEvent(name: "lastFilterReminder", value: st.lastFilterReminder.toString())
    }
    if (st.reminderIntervalDays != null) {
        sendEvent(name: "reminderIntervalDays", value: st.reminderIntervalDays as BigDecimal)
    }
    if (st.remindersEnabled != null) {
        sendEvent(name: "remindersEnabled", value: st.remindersEnabled.toString())
    }
    if (st.cloudStatus != null) {
        sendEvent(name: "cloudStatus", value: st.cloudStatus)
    }
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
