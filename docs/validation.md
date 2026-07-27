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

- [ ] Each zone with an adapter gets thermostat, indoor, diagnostics, and filter children
- [ ] Zones with `hasSensor` also get a wireless sensor child
- [ ] Manual app update / hub restart does not duplicate children

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
- [ ] Indoor humidity appears when API provides `humidity`
- [ ] Wireless child reports temp, humidity, battery, RSSI (if sensor paired)
- [ ] Diagnostics child reports firmware version and router RSSI
- [ ] Filter child reports `lastFilterReminder` timestamp when configured

## Resilience

- [ ] Transient API failure: last-known values retained
- [ ] Three consecutive device-detail failures: `cloudStatus` = `stale`
- [ ] API `connected: false`: `cloudStatus` = `offline`
- [ ] Zone removed from account: children marked `stale`, not auto-deleted

## Automations

- [ ] Rule Machine Custom Action: `setComfortMode` with `dry` or `fan`
- [ ] Rule Machine Custom Action: `setComfortFanSpeed` with extended speeds
- [ ] Rule Machine Custom Action: `setVanePosition`

## Cleanup

- [ ] Stale children listed on app cleanup page
- [ ] Manual deletion of stale child from Devices page succeeds
