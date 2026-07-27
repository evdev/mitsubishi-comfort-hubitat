# Mitsubishi Comfort Cloud for Hubitat

Hubitat Elevation integration for **Mitsubishi Comfort** / **Kumo Cloud** V3 HVAC zones. Ports API behavior from the Home Assistant integration [smack000/comfort_HA](https://github.com/smack000/comfort_HA).

> **Disclaimer:** Kumo Cloud V3 is an unofficial, reverse-engineered cloud API. Mitsubishi may change it without notice. Use at your own risk.

## Features

- Login with Comfort / Kumo Cloud credentials
- Site selection for multi-site accounts
- Per-zone thermostat control (mode, setpoints, fan, vane)
- Indoor temperature and humidity sensors
- Wireless room sensor support (`hasSensor` zones)
- WiFi adapter diagnostics (firmware, RSSI, router SSID)
- Filter reminder metadata (last reminder date, interval, enabled flag)

## Installation

### Hubitat Package Manager (recommended)

1. Install [Hubitat Package Manager](https://github.com/HubitatCommunity/hubitatpackagemanager) if you do not already have it.
2. Open **Hubitat Package Manager** on your hub.
3. Choose one of these methods:

**Option A — Match URL (fastest)**

1. Tap **Match URL**
2. Paste this manifest URL:

   `https://raw.githubusercontent.com/evdev/mitsubishi-comfort-hubitat/main/packageManifest.json`

3. Install **Mitsubishi Comfort Cloud** (app + all drivers)

**Option B — Custom repository**

1. In HPM, open **Settings** → **Bundle Manager Repositories**
2. Add this repository URL:

   `https://raw.githubusercontent.com/evdev/mitsubishi-comfort-hubitat/main/repository.json`

3. Return to HPM, search for **Mitsubishi Comfort Cloud**, and install

4. In Hubitat: **Apps** → **Add User App** → **Mitsubishi Comfort**
5. Enter your Comfort app email and password (the page reloads after each field)
6. Select your site when the dropdown appears
7. Choose poll interval and tap **Done**

Child devices are created automatically after the first successful discovery.

### Manual install

1. In Hubitat: **Drivers Code** → **+ New Driver** → paste each file from `drivers/` (one driver per save)
2. **Apps Code** → **+ New App** → paste `apps/mitsubishi-comfort-app.groovy` → **Save**
3. **Apps** → **Add User App** → **Mitsubishi Comfort**
4. Complete credentials, site selection, and polling preferences as above

## Devices created per zone

| Child device | Purpose |
|---|---|
| `{Zone} Thermostat` | HVAC control |
| `{Zone} Indoor` | Built-in room temp / humidity |
| `{Zone} Wireless` | External sensor (if paired) |
| `{Zone} Diagnostics` | Adapter firmware / WiFi signal |
| `{Zone} Filter` | Filter reminder metadata |

## Fan speed mapping

| Comfort app | API value |
|---|---|
| Auto | `auto` |
| Quiet | `superQuiet` |
| Low | `quiet` |
| Medium | `low` |
| High | `powerful` |
| Powerful | `superPowerful` |

## Vane position mapping

| Comfort app | API value |
|---|---|
| Auto | `auto` |
| Swing | `swing` |
| Lowest | `vertical` |
| Low | `midvertical` |
| Middle | `midpoint` |
| High | `midhorizontal` |
| Highest | `horizontal` |

## Hubitat notes

- Temperature display follows your hub's **F/C** setting (`location.temperatureScale`).
- Mitsubishi proprietary F↔C lookup tables are used for thermostat and indoor room temperature so setpoints match the Comfort app.
- Extended modes `dry` and `fan` are exposed via `supportedThermostatModes` JSON. The Dashboard thermostat tile may honor these; **Rule Machine** standard thermostat actions may require **Custom Action** for nonstandard modes.
- Custom commands `setComfortMode` and `setComfortFanSpeed` are reliable fallbacks for automations.
- Vane control uses `setVanePosition` with Comfort app labels.
- `cloudStatus` reports `online`, `offline`, or `stale`. Stale children are never auto-deleted.

## Troubleshooting

| Symptom | Check |
|---|---|
| No sites listed | Reopen app after saving credentials; verify Comfort app login works |
| Devices show stale | Cloud poll failures; check hub internet and logs |
| Setpoint drift in °F | Expected if hub uses °C; verify `location.temperatureScale` |
| Commands ignored | Cloud rate-limits rapid changes; wait 5+ seconds between adjustments |

Enable **Debug logging** in app settings (auto-disables after 30 minutes). Check **Logs** for lines prefixed with device serial suffixes.

## Uninstall

Remove the app from **Apps**. Child devices are removed on uninstall. Stale children from prior configs can be deleted manually from **Devices**.

## Credits

- [jjustinwilson](https://github.com/jjustinwilson/comfort_HA) — original Kumo Cloud V3 reverse engineering
- [smack000](https://github.com/smack000/comfort_HA) — coordinator refactor, sensors, command caching
- [ekiczek](https://github.com/ekiczek/comfort_HA) — Mitsubishi F/C temperature lookup tables
- [tw3rp](https://github.com/jjustinwilson/comfort_HA) — dual setpoint support, rate limiting

Licensed under the MIT License (see [LICENSE](LICENSE)).
