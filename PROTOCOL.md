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
| OBD2 interface | `obd2` | *(future — hardware TBD)* |

---

## Topic Hierarchy

```
fiesta/
├── buttons                     # Button press/release events
├── sensors                     # Car sensor telemetry (temp, pressure)
├── can/
│   ├── 201                     # MS-CAN 0x201: RPM, speed, throttle (50 Hz)
│   ├── 360                     # MS-CAN 0x360: Brake pedal 3-level (100 Hz)
│   ├── 420                     # MS-CAN 0x420: Coolant temp + brake (10 Hz)
│   └── 428                     # MS-CAN 0x428: Battery voltage (10 Hz)
├── obd2                        # OBD2 vehicle diagnostics (RESERVED — schema TBD)
├── chat                        # Two-way text chat (car ↔ pit crew)
├── pit/
│   └── window                  # Pit window state from Android
└── device/
    ├── buttons-box/
    │   └── status              # Online/offline (LWT)
    ├── telemetry/
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
| `fiesta/chat` | pitstopper, pitcrew-N | 1 | No | — |
| `fiesta/can/201` | can-poller | 0 | No | — |
| `fiesta/can/360` | can-poller | 0 | No | — |
| `fiesta/can/420` | can-poller | 0 | No | — |
| `fiesta/can/428` | can-poller | 0 | No | — |
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
