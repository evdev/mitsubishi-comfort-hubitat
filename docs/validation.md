# Live hub validation matrix

Use this checklist after installing on a Hubitat hub with a real Comfort Cloud account.

## Setup

- [ ] Invalid credentials show an error on the setup page (no child devices created)
- [ ] Valid credentials load the site dropdown after reopening the app
- [ ] Multi-site account: correct site selection creates only that site's zones
- [ ] Hub restart: app resumes polling without re-entering credentials

## Authentication

- [ ] Token refresh after ~20 minutes (no auth errors in logs)
- [ ] Simulated 401 (if possible): app refreshes token and retries once

## Discovery

- [ ] Each zone with an adapter gets one app-owned thermostat
- [ ] Indoor, diagnostics, and filter devices are nested children of that thermostat (not app siblings)
- [ ] Zones with `hasSensor` also get a wireless sensor nested under the thermostat
- [ ] Devices list can collapse the thermostat to hide nested components
- [ ] Manual app update / hub restart does not duplicate children
- [ ] Upgrade from 1.1.x: legacy app-owned indoor/filter/diagnostics/wireless devices are removed and recreated under the thermostat
- [ ] Thermostat device IDs are unchanged after upgrade; satellite device IDs are new

## Thermostat control

- [ ] `off`, `heat`, `cool`, `auto`, `dry`, `fan` modes (as supported by unit profile)
- [ ] Single setpoint in heat/cool modes matches Comfort app within 1°F
- [ ] Dual setpoints in auto mode (`heatingSetpoint` + `coolingSetpoint`)
- [ ] Fan speeds: auto, quiet, low, medium, high, powerful
- [ ] Vane positions: auto, swing, lowest, low, middle, high, highest
- [ ] `thermostatOperatingState` shows heating/cooling/idle appropriately in auto

## Temperature units

- [ ] Hub in °F: setpoints match Comfort app labels
- [ ] Hub in °C: setpoints shown in °C at 0.5° steps

## Command behavior

- [ ] Rapid setpoint changes coalesce (no duplicate commands within 5 seconds)
- [ ] Optimistic UI update appears immediately after command
- [ ] Failed command reverts to last cloud state within one poll cycle

## Sensors

- [ ] Indoor child reports room temperature
- [ ] Indoor humidity appears from zone/device `humidity` or local MHK2 `indoorHumid`
- [ ] Indoor `cloudStatus` matches the thermostat (`online` / `offline` / `stale`)
- [ ] Indoor extra attributes (`tempSource`, `activeThermistor`) appear only when the unit reports them
- [ ] Wireless child reports temp, humidity, battery, RSSI (if sensor paired)
- [ ] Diagnostics child reports firmware version and router RSSI
- [ ] Filter child reports `filterDirty` when the unit reports it, plus `lastFilterReminder` when configured
- [ ] Thermostat reports `defrost` / `standby` when the unit reports those flags

## Resilience

- [ ] Transient API failure: last-known values retained
- [ ] Three consecutive device-detail failures: `cloudStatus` = `stale`
- [ ] API `connected: false` with no local path: `cloudStatus` = `offline`
- [ ] Successful local poll: Indoor and thermostat `cloudStatus` = `online` even if cloud `connected` is stale
- [ ] Zone removed from account: thermostat (and nested components) marked `stale`, not auto-deleted

## Automations

- [ ] Rule Machine Custom Action: `setComfortMode` with `dry` or `fan`
- [ ] Rule Machine Custom Action: `setComfortFanSpeed` with extended speeds
- [ ] Rule Machine Custom Action: `setVanePosition`

## Cleanup

- [ ] Stale thermostats listed on app cleanup page
- [ ] Manual deletion of a stale thermostat from Devices page also removes nested components

## Local control

- [ ] After cloud login, **Refresh local credentials** populates passwords (check logs)
- [ ] Manual unit IP entry enables local polls (`connectionPath` = `local`)
- [ ] Subnet field is pre-filled from the hub LAN prefix (editable)
- [ ] Each zone IP field shows Found / Not set / Waiting for local password
- [ ] **Find missing unit IPs** fills the matching IP fields and the Find unit IPs page shows progress
- [ ] Blocked scan (no passwords, all IPs set, bad subnet) shows a reason instead of failing silently
- [ ] Mode/setpoint/fan/vane commands work via local path
- [ ] Local command latency is lower than cloud-only path
- [ ] Cloud fallback works when local IP is wrong (degraded → `connectionPath` = `cloud`)

## Offline local-only mode

- [ ] With all IPs + cached creds, disconnect WAN: thermostats still poll and accept commands
- [ ] `connectionPath` = `offline` while cloud is unreachable
- [ ] Filter child retains last-known values (no cloud refresh)
- [ ] Reconnect internet: app resumes cloud sync without re-entering credentials
