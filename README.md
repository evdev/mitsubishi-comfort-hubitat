# Mitsubishi Comfort for Hubitat

Hubitat Elevation integration for **Mitsubishi Comfort** / **Kumo Cloud** V3 HVAC zones. Supports **hybrid local LAN control** (like Home Assistant's `mitsubishi_comfort` / `pykumo`) plus cloud fallback. Cloud API behavior ported from [smack000/comfort_HA](https://github.com/smack000/comfort_HA).

> **Disclaimer:** Kumo Cloud V3 is an unofficial, reverse-engineered cloud API. Mitsubishi may change it without notice. Use at your own risk.

## Features

- Login with Comfort / Kumo Cloud credentials
- Site selection for multi-site accounts
- **Local LAN control** when unit IPs and credentials are cached (lower latency, works offline)
- Per-zone thermostat control (mode, setpoints, fan, vane; `defrost` / `standby` when reported)
- Indoor temperature and humidity sensors (MHK2 / zone humidity when the unit reports it)
- Wireless room sensor support (`hasSensor` zones)
- WiFi adapter diagnostics (firmware, RSSI, router SSID)
- Filter dirty flag plus reminder metadata (last reminder date, interval, enabled flag)

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

Child devices are created automatically after the first successful discovery. Each zone appears as a thermostat; indoor, filter, diagnostics, and wireless sensors nest under that thermostat and can be collapsed in the Devices list.

### Manual install

1. In Hubitat: **Drivers Code** → **+ New Driver** → paste each file from `drivers/` (one driver per save)
2. **Apps Code** → **+ New App** → paste `apps/mitsubishi-comfort-app.groovy` → **Save**
3. **Apps** → **Add User App** → **Mitsubishi Comfort**
4. Complete credentials, site selection, and polling preferences as above

## Devices created per zone

The app creates one thermostat per HVAC zone. That thermostat then creates nested component devices:

| Device | Parent | Purpose |
|---|---|---|
| `{Zone} Thermostat` | App | HVAC control; `defrost` / `standby` when reported |
| `{Zone} Indoor` | Thermostat | Built-in room temp / humidity; `tempSource` / `activeThermistor` when reported |
| `{Zone} Wireless` | Thermostat | External sensor (if paired) |
| `{Zone} Diagnostics` | Thermostat | Adapter firmware / WiFi signal |
| `{Zone} Filter` | Thermostat | Live `filterDirty` flag plus reminder metadata |

In **Devices**, collapse a thermostat to hide its nested sensors. Component devices cannot be deleted or have their driver changed independently; removing the thermostat removes them.

### Upgrading from 1.1.x

Indoor, filter, diagnostics, and wireless devices are recreated as children of the thermostat. Thermostat device IDs are unchanged. Re-select the new satellite devices in Rule Machine, dashboards, and other apps that referenced the old ones. Open the Mitsubishi Comfort app and tap **Done**, or wait for the next poll, to run the migration.

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
- `cloudStatus` reports `online`, `offline`, or `stale` on the thermostat and every nested component, using the same reachability rules. Stale thermostats are never auto-deleted.
- Indoor humidity comes from Comfort Cloud (`humidity` on the zone or device) or a local MHK2 wall controller (`indoorHumid`). Wireless PAC humidity stays on the Wireless child.
- Indoor `tempSource` / `activeThermistor` appear when the unit reports which sensor it is using.
- Thermostat `defrost` / `standby` and Filter `filterDirty` appear when the unit reports those flags.
- `connectionPath` on the thermostat reports `local`, `cloud`, or `offline` (local-only when internet is down).

## Local control

When **Prefer local LAN control** is enabled (default), the app talks directly to each Wi-Fi adapter on your LAN (`HTTP PUT` to port 80) instead of routing commands through Kumo Cloud. This matches the protocol used by [pykumo](https://github.com/dlarrick/pykumo) and Home Assistant's [mitsubishi-comfort](https://github.com/nikolairahimi/mitsubishi-comfort) library.

**First-time setup requires internet** to discover zones and fetch per-unit local passwords (via Kumo Socket.IO). After that:

1. Enter each zone's **LAN IP** in the app settings (recommended; use DHCP reservations on your router).
2. Optionally enable **Scan subnet for unit IPs** to probe your network.
3. With cached credentials + IPs, **Allow offline local control** keeps thermostats working when the internet or Kumo Cloud is down.

Filter reminder metadata still requires cloud access when online. In offline mode, filter values show last-known data.

## Troubleshooting

| Symptom | Check |
|---|---|
| No sites listed | Reopen app after saving credentials; verify Comfort app login works |
| Devices show stale | Cloud poll failures; check hub internet and logs |
| Setpoint drift in °F | Expected if hub uses °C; verify `location.temperatureScale` |
| Commands ignored | Cloud rate-limits rapid changes; wait 5+ seconds between adjustments |
| Local control not working | Enter unit IP in app settings; use **Refresh local credentials** after cloud login |
| Offline mode not engaging | All zones need password, crypto serial, and IP cached; check **Offline ready** in app |

Enable **Debug logging** in app settings (auto-disables after 30 minutes). Check **Logs** for lines prefixed with device serial suffixes.

## Uninstall

Remove the app from **Apps**. Zone thermostats and their nested components are removed on uninstall. Stale thermostats from prior configs can be deleted manually from **Devices**.

## Credits

- [jjustinwilson](https://github.com/jjustinwilson/comfort_HA) — original Kumo Cloud V3 reverse engineering
- [smack000](https://github.com/smack000/comfort_HA) — coordinator refactor, sensors, command caching
- [ekiczek](https://github.com/ekiczek/comfort_HA) — Mitsubishi F/C temperature lookup tables
- [tw3rp](https://github.com/jjustinwilson/comfort_HA) — dual setpoint support, rate limiting
- [dlarrick/pykumo](https://github.com/dlarrick/pykumo) — local LAN API and V3 credential retrieval
- [nikolairahimi/mitsubishi-comfort](https://github.com/nikolairahimi/mitsubishi-comfort) — HA local control reference implementation

Licensed under the MIT License (see [LICENSE](LICENSE)).
