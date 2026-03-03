# Copilot Instructions — fiesta

## Repository Structure

This is a monorepo that coordinates all hardware and software components of an in-car pit radio / race telemetry system. It contains four git submodules and one in-tree project:

| Directory | Language | Role |
|-----------|----------|------|
| `fiesta-buttons/` | C / ESP-IDF | ESP32-C6 button box firmware |
| `fiesta-hardware/` | C / ESP-IDF | ESP32 car telemetry unit firmware |
| `carpi/` | Shell / config | Raspberry Pi Zero 2 W — WiFi AP + MQTT broker |
| `pitstopper/` | Java / Android | In-car tablet app (pit window timer + live timing) |
| `pitcrew/` | Java / Android | Pit crew phone app (early stage) |

`PROTOCOL.md` is the canonical reference for all MQTT message schemas and topic hierarchy.

---

## System Architecture

All nodes connect to a Mosquitto MQTT broker on `carpi` (RPi Zero 2 W). In prod mode the Pi creates the `fiesta-network` WiFi AP (192.168.4.1); in dev mode it joins an existing LAN (10.0.0.211). The hostname `broker` is resolved by carpi's dnsmasq on both networks.

```
carpi (RPi)  ←—  WiFi AP / MQTT broker (Mosquitto)  →  broker:1883
    ↑ WiFi STA                                          ↑ WiFi STA
fiesta-buttons (ESP32-C6)                   fiesta-hardware (ESP32 + ADS1115)
    publishes: fiesta/buttons                   publishes: fiesta/sensors
                                            pitstopper (Android, in-car tablet)
                                                publishes: fiesta/pit/window, fiesta/chat
                                                subscribes: fiesta/buttons, fiesta/sensors
                                            pitcrew (Android, pit crew phones)
                                                publishes/subscribes: fiesta/chat
```

---

## MQTT Protocol

**Topic hierarchy** (all prefixed `fiesta/`):
- `fiesta/buttons` — button press events (QoS 1, no retain)
- `fiesta/sensors` — car telemetry: `{"oil_temp":<int>, "oil_pres":<float>, "water_temp":<int>, "gas_pres":<float>}` (QoS 0, retain)
- `fiesta/pit/window` — pit window state: `{"state":"OPEN"|"CLOSED", "window":<int>}` (QoS 1, retain)
- `fiesta/chat` — two-way text messages: `{"from":"<device-id>", ...}` (QoS 1, no retain)
- `fiesta/device/<device-id>/status` — online/offline LWT for every device (QoS 1, retain)

**Device IDs**: `buttons-box`, `telemetry`, `pitstopper`, `pitcrew-1`/`pitcrew-2`/…

**Button names**: `PIT`, `YES`, `FCK`, `STINT`, `NO` — states: `PRESSED`, `DEPRESSED`

**LWT pattern**: Every device configures `fiesta/device/<id>/status` → `{"status":"offline"}` as LWT, and publishes `{"status":"online"}` on connect.

---

## fiesta-buttons (ESP32-C6 Firmware)

**Requires ESP-IDF ≥ 5.5.2.** Target: ESP32-C6 (RISC-V).

### Build commands
```sh
idf.py build                        # secondary unit
idf.py -DPRIMARY=1 build            # primary unit (flag currently unused)
idf.py -p COMx flash monitor
idf.py fullclean
```

On Windows with COM port:
```sh
idf.py.exe -p COM4 -DPRIMARY=1 -DFORD=1 build flash monitor
```

### Architecture
Boot sequence: NVS init → netif init → event loop → `led_status_init` → `wlan_start` (blocks until IP, reboots on failure) → `mqttcomm_start` → `button_handler_start`

- `wlan.c` — WiFi STA; credentials (`fiesta-network` / `fiesta-network-123`) as `#define` at top of file
- `mqttcomm.c` — connects to `mqtt://broker:1883`; client ID `buttons-box`; reconnects every 5 s
- `button_handler.c` — 5 GPIO buttons via `espressif/button` component; GPIO 18=NO, 19=STINT, 20=FCK, 21=YES, 14=PIT; only `BUTTON_PRESS_DOWN` handled
- `led_status.c` — GPIO 8; solid = MQTT connected, ~2 Hz flash = disconnected
- `status_broadcaster.c` — formats JSON and publishes to `fiesta/buttons`

### Conventions
- All `.c` files must be listed explicitly in `main/CMakeLists.txt` `idf_component_register(SRCS ...)` — new files won't compile otherwise
- ESP-IDF component dependencies managed via `main/idf_component.yml`
- Custom partition table in `partitions.csv` (OTA slots + NVS + Zigbee storage)

---

## fiesta-hardware (ESP32 Telemetry Firmware)

**Requires ESP-IDF.** Target: ESP32 + external ADS1115 ADC via I2C.

### Build commands
```sh
idf.py build
idf.py -p COMx flash monitor
```

### Architecture
Boot: NVS → netif → event loop → `state_setup` → `led_status_init` → `wlan_start` → `mqttcomm_start` → `analog_reader_start`

- `analog_reader.c` — reads ADS1115 (I2C port 0); converts voltage divider readings to oil temp (NTC lookup table in `res_values[]`), oil pressure, gas pressure
- `state.c` — shared state (`struct mcu_data`) with setters/getter; updated by `analog_reader`, read by `status_broadcaster`
- `mqttcomm.c` — auto-detects broker from WiFi gateway IP (`esp_netif_get_ip_info` → `.gw.addr`), unlike fiesta-buttons which uses hostname
- `data.h` — defines `struct mcu_data` with nested `struct car_sensor { int temp; double preassure; }`
- `out/sensors.c` / `out/sensors.h` — generated sensor output helpers

### Conventions
- Same `main/CMakeLists.txt` requirement as fiesta-buttons
- Components: `ads111x`, `i2cdev`, `esp_idf_lib_helpers`, `net-logging` (local under `components/`)
- Web UI assets served from `data/` directory (SPIFFS)

---

## pitstopper (Android — in-car tablet)

Pit window timer with GPS auto-stop and SpeedHive live timing. **See `pitstopper/.github/copilot-instructions.md` for full details.**

### Build commands (run from `pitstopper/`)
```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease            # AAB for Play Store
./gradlew test                     # unit tests (42 cases)
./gradlew test --tests "PitWindowAlertManagerTest"   # single class
./gradlew connectedAndroidTest     # instrumented (needs device)
./gradlew clean
```

- Namespace: `at.semmal.pitstopper` | Min SDK: 24 | Target SDK: 36 | Java 11
- SpeedHive API credentials go in `app/src/main/assets/speedhive.properties` (gitignored)

### Release process
See `pitstopper/COPILOT-INSTRUCTIONS.md` for the exact step-by-step release procedure (version bump → build → sign verify → copy APK → VERSIONS.md → git commit + **tag**).

---

## pitcrew (Android — pit crew phones)

Early-stage pit crew companion app. Namespace: `app.klabs.pitcrew` | Min SDK: 24 | Target SDK: 36 | Java 11

### Build commands (run from `pitcrew/`)
```bash
./gradlew assembleDebug
./gradlew test
```

---

## carpi (Raspberry Pi infrastructure)

Network mode is selected at boot by a physical jumper on **GPIO4 (pin 7)**:
- **Open (GPIO HIGH)** → Prod mode: creates `fiesta-network` AP at `192.168.4.1`
- **GPIO4 → GND (GPIO LOW)** → Dev mode: joins existing LAN, gets `10.0.0.211` via DHCP

SSH in dev mode: `semtex@10.0.0.211`

Key services: `mosquitto` (MQTT broker), `dnsmasq` (resolves `broker` → Pi IP), `NetworkManager`.

---

## Network / Credentials

| Parameter | Value |
|-----------|-------|
| WiFi SSID | `fiesta-network` |
| WiFi password | `fiesta-network-123` |
| Broker hostname | `broker` (dnsmasq) |
| Broker port | `1883` |
| Pi IP (prod) | `192.168.4.1` |
| Pi IP (dev) | `10.0.0.211` |
