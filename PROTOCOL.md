# Fiesta MQTT Communication Protocol

This document defines the MQTT messaging protocol used by all devices in the Fiesta pit radio system.

## System Overview

```
┌─────────────────────────────────────────────────────┐
│               carpi  (Raspberry Pi Zero 2W)          │
│   Mosquitto MQTT Broker  ·  10.0.0.211 : 1883       │
│   WiFi Access Point  ·  SSID: fiesta-network          │
│   can-bridge service  (CAN → MQTT)                    │
└──────────┬──────────────────────┬────────────────────┘
           │ WiFi STA             │ WiFi STA
     ┌─────┴──────┐        ┌──────┴──────┐
     │   fiesta-  │        │   fiesta-   │
     │   buttons  │        │   hardware  │
     │  (ESP32)   │        │  (ESP32 +   │
     │            │        │  ADS1115)   │
     └────────────┘        └─────────────┘
           │ WiFi STA             │ TCP :23
     ┌─────┴──────────────┐ ┌────┴────────┐
     │   pitstopper       │ │   WiCAN     │
     │   (Android)        │ │  (ESP32RET) │
     │   Subscribes and   │ │  MS-CAN bus │
     │   Publishes        │ └─────────────┘
     └────────────────────┘
```

All devices connect to the carpi WiFi network (`fiesta-network`). The carpi Mosquitto instance is the sole MQTT broker, reachable at hostname `broker` (resolved via carpi dnsmasq).

---

## Connection Parameters

| Parameter | Value |
|-----------|-------|
| Broker host | `broker` (hostname via carpi dnsmasq) / `192.168.4.1` (prod) / `10.0.0.211` (dev) |
| Port | `1883` (MQTT, no TLS) |
| Authentication | None (anonymous) |
| Keep-alive | 60 seconds |
| Reconnect timeout | 5 seconds |
| Protocol version | MQTT 3.1.1 |

### ESP32 Broker Connection

ESP32 devices connect to `mqtt://broker:1883`. The hostname `broker` is resolved by carpi's dnsmasq, which serves as the DNS server for all devices on the network.

### Android Client

The Android `pitstopper` app connects to `mqtt://broker:1883` (resolved by carpi dnsmasq) or falls back to `mqtt://10.0.0.211:1883`.

---

## Device Identifiers

Each device has a unique string ID used in status topics and client IDs.

| Device | ID | Hardware |
|--------|----|----------|
| Pit radio button box | `buttons-box` | ESP32 (`fiesta-buttons`) |
| Car telemetry unit | `telemetry` | ESP32 + ADS1115 (`fiesta-hardware`) |
| In-car tablet app | `pitstopper` | Android tablet in car |
| Pit crew app (first instance) | `pitcrew-1` | Android phone/tablet (`pitcrew`) |
| Pit crew app (additional instances) | `pitcrew-2`, `pitcrew-3`, … | Android phone/tablet (`pitcrew`) |
| MS-CAN bridge | `can-poller` | WiCAN / ESP32RET via TCP (`fiesta-can-bridge`) |
| TPMS bridge | `tpms-bridge` | CC1101 433 MHz FSK on Pi Zero 2W (`fiesta-tire-pres-bridge`) |
| Tire temperature (front-left) | `tire-temp-FL` | ESP32 + MLX90640 (`fiesta-tire-temp`) |
| Tire temperature (front-right) | `tire-temp-FR` | ESP32 + MLX90640 (`fiesta-tire-temp`) |
| Tire temperature (rear-left) | `tire-temp-RL` | ESP32 + MLX90640 (`fiesta-tire-temp`) |
| Tire temperature (rear-right) | `tire-temp-RR` | ESP32 + MLX90640 (`fiesta-tire-temp`) |
| OBD2 interface | `obd2` | *(future — hardware TBD)* |

---

## Topic Hierarchy

```
fiesta/
├── buttons                     # Button press/release events
├── sensors                     # Car sensor telemetry (temp, pressure)
├── tpms/
│   ├── fl                      # Front-left tyre: pressure (bar) + temp (°C)
│   ├── fr                      # Front-right tyre
│   ├── rl                      # Rear-left tyre
│   └── rr                      # Rear-right tyre
├── events                      # Alert state transitions + chat events
├── can/
│   ├── 201                     # MS-CAN 0x201: RPM, speed, throttle (50 Hz)
│   ├── 360                     # MS-CAN 0x360: Brake pedal 3-level (100 Hz)
│   ├── 420                     # MS-CAN 0x420: Coolant temp + brake (10 Hz)
│   └── 428                     # MS-CAN 0x428: Battery voltage (10 Hz)
├── tire-temp/
│   ├── FL                      # Front-left tyre thermal profile (segment data)
│   ├── FL/raw                  # Front-left tyre raw 32×24 pixel matrix
│   ├── FR                      # Front-right tyre
│   ├── FR/raw
│   ├── RL                      # Rear-left tyre
│   ├── RL/raw
│   ├── RR                      # Rear-right tyre
│   └── RR/raw
├── obd2                        # OBD2 vehicle diagnostics (RESERVED — schema TBD)
├── chat                        # Two-way text chat (car ↔ pit crew)
├── brightness                     # Display brightness from tablet (0–100%)
├── pit/
│   └── window                  # Pit window state from Android
└── device/
    ├── buttons-box/
    │   └── status              # Online/offline (LWT)
    ├── telemetry/
    │   └── status              # Online/offline (LWT)
    ├── tpms-bridge/
    │   └── status              # Online/offline (LWT)
    ├── tire-temp-FL/
    │   └── status              # Online/offline (LWT)
    ├── tire-temp-FR/
    │   └── status              # Online/offline (LWT)
    ├── tire-temp-RL/
    │   └── status              # Online/offline (LWT)
    ├── tire-temp-RR/
    │   └── status              # Online/offline (LWT)
    ├── pitstopper/
    │   └── status              # Online/offline (LWT)
    ├── pitcrew-1/
    │   └── status              # Online/offline (LWT)
    └── obd2/
        └── status              # Online/offline (LWT, future)
```

---

## Topics

### `fiesta/buttons`

**Publisher:** `fiesta-buttons` (ESP32 pit radio button box)  
**Subscribers:** `pitstopper` (Android), any monitoring client  
**QoS:** 1 (at least once)  
**Retain:** No

Published on every button press and release.

```json
{
  "button": "<BUTTON_NAME>",
  "state": "<BUTTON_STATE>"
}
```

| Field | Type | Values |
|-------|------|--------|
| `button` | string | `PIT`, `YES`, `FCK`, `TALK`, `NO` |
| `state` | string | `PRESSED`, `DEPRESSED` |

**Examples:**
```json
{"button": "PIT", "state": "PRESSED"}
{"button": "PIT", "state": "DEPRESSED"}
{"button": "NO",  "state": "PRESSED"}
{"button": "YES", "state": "DEPRESSED"}
```

---

### `fiesta/sensors`

**Publisher:** `fiesta-hardware` (ESP32 telemetry unit)  
**Subscribers:** `pitstopper` (Android), any monitoring client  
**QoS:** 0 (fire and forget)  
**Retain:** Yes (broker keeps last value for late-joining clients)

Published periodically with the latest ADC sensor readings.

```json
{
  "oil_temp":   <int>,
  "oil_pres":   <float>,
  "water_temp": <int>,
  "gas_pres":   <float>
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `oil_temp` | integer | °C | Engine oil temperature |
| `oil_pres` | float | bar | Engine oil pressure |
| `water_temp` | integer | °C | Engine coolant temperature |
| `gas_pres` | float | bar | Fuel pressure |

**Example:**
```json
{"oil_temp": 92, "oil_pres": 3.85, "water_temp": 88, "gas_pres": 1.20}
```

> **Note:** Fields not yet available from hardware (e.g. `water_temp`) may be omitted or set to `null`. Consumers must handle missing fields gracefully.

---

### `fiesta/tpms/{position}`

**Publisher:** `tpms-bridge` (CC1101 TPMS receiver on carpi, `fiesta-tire-pres-bridge`)  
**Subscribers:** `pitstopper` (Android), any monitoring client  
**QoS:** 0 (fire and forget)  
**Retain:** Yes (last known value available to late-joining clients)

One topic per tyre position. Published on each sensor event, after burst deduplication
(sensors send 3–6 identical packets per event; only the first is forwarded).

```json
{
  "pres_bar": <float|null>,
  "temp_c":   <int>,
  "alarm":    <bool>
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `pres_bar` | float or null | bar | Tyre pressure. `null` when sensor is not on valve (alarm state). Resolution: 0.025 bar |
| `temp_c` | integer | °C | Sensor temperature. Resolution: 1°C |
| `alarm` | boolean | — | `true` when sensor reports alarm (not on valve, or low-pressure warning) |

**Topic positions:** `fl` (front-left), `fr` (front-right), `rl` (rear-left), `rr` (rear-right)

**Examples:**
```json
{"pres_bar": 2.100, "temp_c": 22, "alarm": false}
{"pres_bar": null,  "temp_c": 19, "alarm": true}
```

> **Sensor hardware:** Jansite Solar aftermarket TPMS valve sensors (433.92 MHz, 2-FSK,
> Manchester encoding, 10.4 kbps). Received via TI CC1101 transceiver connected to the Pi
> Zero 2W over SPI. Full protocol documented in `fiesta-tire-pres-bridge/AGENT.md`.

---

### `fiesta/pit/window`

**Publisher:** `pitstopper` (Android)  
**Subscribers:** `fiesta-buttons` (ESP32), any display device  
**QoS:** 1 (at least once)  
**Retain:** Yes (devices that reconnect learn the current state immediately)

Published when a pit window opens or closes. ESP32 devices can use this to trigger visual/audio alerts (e.g. LED flash, buzzer).

```json
{
  "state":  "<PIT_WINDOW_STATE>",
  "window": <int>
}
```

| Field | Type | Values | Notes |
|-------|------|--------|-------|
| `state` | string | `OPEN`, `CLOSED` | Current pit window state |
| `window` | integer | ≥ 1 | Pit window number within the session (increments each cycle) |

**Examples:**
```json
{"state": "OPEN",   "window": 1}
{"state": "CLOSED", "window": 1}
{"state": "OPEN",   "window": 2}
```

---

### `fiesta/brightness`

**Publisher:** `pitstopper` (Android)
**Subscribers:** `fiesta-led` (ESP32 LED controller), any display device
**QoS:** 0 (fire and forget)
**Retain:** Yes (devices that reconnect learn the current brightness immediately)

Published by the tablet to control display brightness on LED devices. Intended for dimming the LED strip based on ambient conditions or user preference.

```json
{
  "brightness": <int>
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `brightness` | integer | % | Display brightness, 0–100 (0 = off, 100 = full brightness) |

**Examples:**
```json
{"brightness": 100}
{"brightness": 50}
{"brightness": 0}
```

---

### `fiesta/chat`

**Publishers:** `pitstopper` (in-car tablet), `pitcrew-N` (pit crew Android devices)  
**Subscribers:** `pitstopper`, all `pitcrew-N` devices  
**QoS:** 1 (at least once)  
**Retain:** No

Published whenever a user sends a chat message. All subscribers receive every message; the `from` field identifies the sender so UIs can distinguish "sent" from "received".

```json
{
  "from":           "<device_id>",
  "text":           "<ascii_message>",
  "isNotification": <bool>,
  "isAlert":        <bool>
}
```

| Field | Type | Notes |
|-------|------|-------|
| `from` | string | Sender device ID (e.g. `pitstopper`, `pitcrew-1`) |
| `text` | string | ASCII text, no length limit enforced by protocol |
| `isNotification` | boolean | `true` for informational events (e.g. "PIT" — pit stop requested) |
| `isAlert` | boolean | `true` for urgent alerts (e.g. "FCK", "ALARM") |

`isNotification` and `isAlert` are mutually exclusive; both may be `false` for ordinary chat messages. Receivers must handle missing fields gracefully (treat absent as `false`).

**Examples:**
```json
{"from": "pitstopper",  "text": "PIT",           "isNotification": true,  "isAlert": false}
{"from": "pitstopper",  "text": "fck",            "isNotification": false, "isAlert": true}
{"from": "pitstopper",  "text": "ALARM",          "isNotification": false, "isAlert": true}
{"from": "pitstopper",  "text": "Box this lap",   "isNotification": false, "isAlert": false}
{"from": "pitcrew-1",   "text": "Copy, ready",    "isNotification": false, "isAlert": false}
```

> **Multiple pit crew devices:** Each `pitcrew` app instance must be configured with a unique device ID (`pitcrew-1`, `pitcrew-2`, …) before connecting. All instances see all messages.

---

### `fiesta/events`

**Publisher:** `pitstopper` (Android)
**Subscribers:** Any monitoring client, LED controller, pit crew displays
**QoS:** 0 (fire and forget)
**Retain:** No

Published by the `pitstopper` app when a telemetry value crosses a warning/critical threshold, when the overall alert status changes, or when a chat message is received from the external session. Three event types share this topic:

#### Telemetry alert transition

Published when a metric enters or leaves a warning/critical state.

```json
{
  "type":       "telemetry_alert",
  "metric":     "<METRIC_NAME>",
  "transition": "<TRANSITION>",
  "level":      "<ALERT_LEVEL>",
  "value":      <number>,
  "threshold":  <number>,
  "ts":         <long>
}
```

| Field | Type | Values | Notes |
|-------|------|--------|-------|
| `type` | string | `telemetry_alert` | — |
| `metric` | string | `rpm`, `coolant`, `oil_temp`, `oil_pres`, `battery` | Which sensor triggered |
| `transition` | string | `entered`, `left` | Entering or leaving the alert level |
| `level` | string | `warning`, `critical` | The level being entered or left |
| `value` | number | — | Current sensor reading at time of transition |
| `threshold` | number | — | The threshold that was crossed |
| `ts` | long | — | Epoch milliseconds (`System.currentTimeMillis()`) |

**Threshold directions:**
- **High-is-bad** (RPM, coolant, oil temp): `entered` when value >= threshold
- **Low-is-bad** (oil pressure, battery): `entered` when value <= threshold

**Examples:**
```json
{"type": "telemetry_alert", "metric": "coolant", "transition": "entered", "level": "warning", "value": 101, "threshold": 100, "ts": 1711360000000}
{"type": "telemetry_alert", "metric": "coolant", "transition": "entered", "level": "critical", "value": 112, "threshold": 110, "ts": 1711360005000}
{"type": "telemetry_alert", "metric": "oil_pres", "transition": "entered", "level": "critical", "value": 0.8, "threshold": 1.0, "ts": 1711360010000}
{"type": "telemetry_alert", "metric": "coolant", "transition": "left", "level": "critical", "value": 108, "threshold": 110, "ts": 1711360020000}
```

#### Overall status change

Published when the merged status across all metrics changes. The overall status is the worst of all individual metric levels (critical > warning > ok). As long as any one metric is in a given level, the overall status stays at that level.

```json
{
  "type":     "overall_status",
  "status":   "<ALERT_LEVEL>",
  "previous": "<ALERT_LEVEL>",
  "ts":       <long>
}
```

| Field | Type | Values | Notes |
|-------|------|--------|-------|
| `type` | string | `overall_status` | — |
| `status` | string | `ok`, `warning`, `critical` | New overall level |
| `previous` | string | `ok`, `warning`, `critical` | Previous overall level |
| `ts` | long | — | Epoch milliseconds |

**Example:**
```json
{"type": "overall_status", "status": "critical", "previous": "warning", "ts": 1711360005000}
{"type": "overall_status", "status": "ok", "previous": "warning", "ts": 1711360030000}
```

#### Chat event

Published when a message arrives from the external session (public broker). Mirrors the message onto the local broker for local-only subscribers.

```json
{
  "type":           "chat",
  "from":           "<device_id>",
  "text":           "<message>",
  "isNotification": <bool>,
  "isAlert":        <bool>,
  "ts":             <long>
}
```

| Field | Type | Notes |
|-------|------|-------|
| `type` | string | `chat` |
| `from` | string | Sender device ID from external session |
| `text` | string | Message text |
| `isNotification` | boolean | Informational event flag |
| `isAlert` | boolean | Urgent alert flag |
| `ts` | long | Epoch milliseconds |

**Example:**
```json
{"type": "chat", "from": "pitcrew-1", "text": "Box this lap", "isNotification": false, "isAlert": false, "ts": 1711360000000}
```

---

### `fiesta/can/360`

**Publisher:** `can-poller` (MS-CAN bridge on carpi)  
**Subscribers:** `pitstopper` (Android), `pitcrew-N`, any monitoring client  
**QoS:** 0 (fire and forget)  
**Retain:** No

Published at ~100 Hz with brake pedal state (3-level resolution).

```json
{
  "brake_pedal": "<BRAKE_STATE>"
}
```

| Field | Type | Values | Notes |
|-------|------|--------|-------|
| `brake_pedal` | string | `off`, `touch`, `pressed` | `touch` = foot resting on pedal |

**Example:**
```json
{"brake_pedal": "pressed"}
```

---

### `fiesta/can/420`

**Publisher:** `can-poller` (MS-CAN bridge on carpi)  
**Subscribers:** `pitstopper` (Android), `pitcrew-N`, any monitoring client  
**QoS:** 0 (fire and forget)  
**Retain:** No

Published at ~10 Hz with engine coolant temperature and brake pedal state.

```json
{
  "coolant_c":   <int>,
  "brake_pedal": "<BRAKE_STATE>"
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `coolant_c` | integer | °C | ECU-reported coolant temperature |
| `brake_pedal` | string | — | `off`, `touch`, `pressed` |

**Example:**
```json
{"coolant_c": 92, "brake_pedal": "off"}
```

---

### `fiesta/can/428`

**Publisher:** `can-poller` (MS-CAN bridge on carpi)  
**Subscribers:** `pitstopper` (Android), `pitcrew-N`, any monitoring client  
**QoS:** 0 (fire and forget)  
**Retain:** No

Published at ~10 Hz with battery/charging system voltage.

```json
{
  "battery_v": <float>
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `battery_v` | float | V | Battery voltage (0.1 V resolution) |

**Example:**
```json
{"battery_v": 14.2}
```

---

### `fiesta/can/201`

**Publisher:** `can-poller` (MS-CAN bridge on carpi, via `fiesta-can-bridge`)  
**Subscribers:** `pitstopper` (Android), `pitcrew-N`, any monitoring client  
**QoS:** 0 (fire and forget)  
**Retain:** No

Published at ~50 Hz (every decoded CAN 0x201 frame) with live engine and speed data decoded from the MS-CAN bus.

```json
{
  "rpm":          <int>,
  "speed_kmh":    <float>,
  "throttle_pct": <float>
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `rpm` | integer | RPM | Engine RPM (0.25 RPM resolution, integer-rounded) |
| `speed_kmh` | float | km/h | Vehicle speed (0.01 km/h resolution) |
| `throttle_pct` | float | % | Gas pedal position, 0.0–100.0 |

**Example:**
```json
{"rpm": 3200, "speed_kmh": 87.5, "throttle_pct": 42.3}
```

> **Note:** Published only when the CAN bridge is connected to a GVRET device and receiving 0x201 frames. Additional `fiesta/can/<id>` topics may be added as more CAN IDs are decoded.

---

### `fiesta/tire-temp/{position}`

**Publisher:** `tire-temp-{position}` (ESP32 + MLX90640, `fiesta-tire-temp`)  
**Subscribers:** `pitstopper` (Android), any monitoring client  
**QoS:** 1 (at least once)  
**Retain:** No

Published once per second with the segmented thermal profile of the tyre. The 32×24 sensor
image is divided into three vertical thirds (outside / center / inside) and the average
temperature of each third is reported. When no tyre is detected in the frame (e.g. sensor
not yet pointed at a tyre, or cold tyre indistinguishable from background), `detected` is
`false` and the zone temperatures are omitted.

**Topic positions:** `FL` (front-left), `FR` (front-right), `RL` (rear-left), `RR` (rear-right)

```json
{
  "ts":       <uint32>,
  "ta":       <float>,
  "outside":  <float>,
  "center":   <float>,
  "inside":   <float>,
  "detected": true,
  "pixels":   <uint16>
}
```

```json
{
  "ts":       <uint32>,
  "ta":       <float>,
  "detected": false,
  "pixels":   <uint16>
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `ts` | uint32 | ms | FreeRTOS tick timestamp (ms since boot) |
| `ta` | float | °C | Ambient temperature reported by the MLX90640 die |
| `outside` | float | °C | Mean temperature of the outer third of the detected region |
| `center` | float | °C | Mean temperature of the center third |
| `inside` | float | °C | Mean temperature of the inner third |
| `detected` | boolean | — | `true` when a tyre-sized hot/cold region is found in the frame |
| `pixels` | uint16 | — | Pixel count of the detected region (0 when `detected` is `false`) |

**Examples:**
```json
{"ts":12500,"ta":22.5,"outside":45.2,"center":52.1,"inside":48.3,"detected":true,"pixels":47}
{"ts":13500,"ta":22.6,"detected":false,"pixels":0}
```

---

### `fiesta/tire-temp/{position}/raw`

**Publisher:** `tire-temp-{position}` (ESP32 + MLX90640, `fiesta-tire-temp`)  
**Subscribers:** Any monitoring or logging client  
**QoS:** 1 (at least once)  
**Retain:** No

Published every frame alongside the segment topic. Contains the full 32×24 = 768-pixel
temperature matrix in row-major order (row 0 = top of sensor field of view). Intended for
offline analysis, calibration, and visualisation — not for real-time display on the car.

```json
{
  "ts":     <uint32>,
  "ta":     <float>,
  "pixels": [<float>, ...]
}
```

| Field | Type | Unit | Notes |
|-------|------|------|-------|
| `ts` | uint32 | ms | FreeRTOS tick timestamp (ms since boot) |
| `ta` | float | °C | Ambient temperature from the MLX90640 die |
| `pixels` | float[768] | °C | Full 32×24 pixel array, 1 decimal place, row-major |

**Example (truncated):**
```json
{"ts":12500,"ta":22.5,"pixels":[23.1,23.2,23.0,...,45.8,52.3,48.1,...]}
```

---

### `fiesta/obd2`

**Publisher:** OBD2 interface device *(future)*  
**Subscribers:** `pitstopper` (Android), any monitoring client  
**QoS:** 0  
**Retain:** Yes  
**Status:** **RESERVED** — schema to be defined when OBD2 hardware is selected

This topic is reserved for vehicle OBD2 diagnostic data (e.g. RPM, throttle position, gear, lambda). The exact message schema will be defined once the OBD2 hardware and available PIDs are known. Consumers should ignore unknown fields to allow forward compatibility.

---

### `fiesta/device/{device_id}/status`

**Publisher:** Each device (as part of connection setup)  
**Subscribers:** All interested parties  
**QoS:** 1  
**Retain:** Yes

Each device announces its presence on connect and configures a Last Will and Testament (LWT) message so the broker automatically publishes `offline` if the device drops without a clean disconnect.

```json
{"status": "<STATUS>"}
```

| Field | Type | Values |
|-------|------|--------|
| `status` | string | `online`, `offline` |

**On connect**, each device publishes:
```json
{"status": "online"}
```

**LWT** (auto-published by broker on unexpected disconnect):
```json
{"status": "offline"}
```

**Topic examples:**
```
fiesta/device/buttons-box/status
fiesta/device/telemetry/status
fiesta/device/pitstopper/status
```

---

## QoS and Retain Summary

| Topic | Publisher | QoS | Retain | LWT |
|-------|-----------|:---:|:------:|:---:|
| `fiesta/buttons` | buttons-box | 1 | No | — |
| `fiesta/sensors` | telemetry | 0 | Yes | — |
| `fiesta/tpms/+` | tpms-bridge | 0 | Yes | — |
| `fiesta/tire-temp/+` | tire-temp-{pos} | 1 | No | — |
| `fiesta/tire-temp/+/raw` | tire-temp-{pos} | 1 | No | — |
| `fiesta/events` | pitstopper | 0 | No | — |
| `fiesta/chat` | pitstopper, pitcrew-N | 1 | No | — |
| `fiesta/can/201` | can-poller | 0 | No | — |
| `fiesta/can/360` | can-poller | 0 | No | — |
| `fiesta/can/420` | can-poller | 0 | No | — |
| `fiesta/can/428` | can-poller | 0 | No | — |
| `fiesta/brightness` | pitstopper | 0 | Yes | — |
| `fiesta/obd2` | obd2 *(future)* | 0 | Yes | — |
| `fiesta/pit/window` | pitstopper | 1 | Yes | — |
| `fiesta/device/+/status` | all devices | 1 | Yes | `{"status":"offline"}` |

---

## Android Subscription Map

### `pitstopper` (in-car tablet)

The `pitstopper` app subscribes to the following topics on broker connect:

| Topic | Purpose |
|-------|---------|
| `fiesta/buttons` | Display button events, trigger UI feedback |
| `fiesta/sensors` | Display live car telemetry |
| `fiesta/chat` | Receive chat messages from pit crew |
| `fiesta/can/+` | Display live CAN bus data (RPM, speed, brake, coolant, battery) |
| `fiesta/obd2` | *(subscribe now, handle fields when schema is defined)* |
| `fiesta/device/+/status` | Show device connection status in UI |

The app publishes to:

| Topic | When |
|-------|------|
| `fiesta/pit/window` | Pit window opens or closes |
| `fiesta/brightness` | Brightness level changes (user adjusts or ambient-based) |
| `fiesta/events` | Telemetry alert transitions, overall status changes, chat events |
| `fiesta/chat` | Driver sends a chat message |
| `fiesta/device/pitstopper/status` | On connect (`online`) and as LWT (`offline`) |

---

### `pitcrew` (pit crew Android devices)

Each `pitcrew` app instance subscribes to:

| Topic | Purpose |
|-------|---------|
| `fiesta/buttons` | Display button events from car |
| `fiesta/sensors` | Display live car telemetry |
| `fiesta/can/+` | Display live CAN bus data (RPM, speed, brake, coolant, battery) |
| `fiesta/chat` | Receive chat messages from car and other pit crew |
| `fiesta/device/+/status` | Show device connection status in UI |

Each instance publishes to:

| Topic | When |
|-------|------|
| `fiesta/chat` | Pit crew member sends a chat message |
| `fiesta/device/{device_id}/status` | On connect (`online`) and as LWT (`offline`) |

---

## Session-Based External Access (Public Broker)

When pit crew members connect over the internet (not on the local `fiesta-network`), all MQTT topics are **prefixed with a session ID** and routed through a public broker. This allows secure-ish (security through obscurity via UUID) communication without VPN or port forwarding.

### Deep Link / QR Code Format

The `pitstopper` app generates a QR code that encodes a deep link URI. Scanning this QR with the stock camera app opens the `pitcrew` app and passes the session parameters via Intent:

```
pitstopper://join?session=<uuid>&host=<broker_host>&port=<broker_port>
```

| Parameter | Type | Required | Default | Notes |
|-----------|------|:--------:|---------|-------|
| `session` | string (UUID) | ✅ | — | Unique session identifier |
| `host` | string | ✅ | `broker.hivemq.com` | MQTT broker hostname |
| `port` | integer | ❌ | `1883` | MQTT broker port |

**Example:**
```
pitstopper://join?session=f47ac10b-58cc-4372-a567-0e02b2c3d479&host=broker.hivemq.com&port=1883
```

Both `pitstopper` and `pitcrew` apps register for the `pitstopper://join` scheme via Android intent filters.

### Session-Prefixed Topics

When using external/public broker access, **all standard `fiesta/` topics are prefixed with the session ID**:

| Local Topic | Session-Prefixed Topic |
|-------------|----------------------|
| `fiesta/chat` | `<session_id>/fiesta/chat` |
| `fiesta/device/<id>/status` | `<session_id>/fiesta/device/<id>/status` |

The message payloads remain identical to the local protocol definitions above.
