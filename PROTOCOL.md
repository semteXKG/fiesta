# Fiesta MQTT Communication Protocol

This document defines the MQTT messaging protocol used by all devices in the Fiesta pit radio system.

## System Overview

```
┌─────────────────────────────────────────────────────┐
│               carpi  (Raspberry Pi Zero 2W)          │
│   Mosquitto MQTT Broker  ·  10.0.0.211 : 1883       │
│   WiFi Access Point  ·  SSID: funkbox-ford-prim/sec  │
└──────────┬──────────────────────┬────────────────────┘
           │ WiFi STA             │ WiFi STA
     ┌─────┴──────┐        ┌──────┴──────┐
     │   fiesta-  │        │   fiesta-   │
     │   buttons  │        │   hardware  │
     │  (ESP32)   │        │  (ESP32 +   │
     │            │        │  ADS1115)   │
     └────────────┘        └─────────────┘
           │ WiFi STA
     ┌─────┴──────────────┐
     │   pitstopper       │
     │   (Android)        │
     │   Subscribes and   │
     │   Publishes        │
     └────────────────────┘
```

All devices connect to the carpi WiFi network. The carpi Mosquitto instance is the sole MQTT broker. The broker address is auto-discovered by ESP32 devices using the WiFi gateway IP.

---

## Connection Parameters

| Parameter | Value |
|-----------|-------|
| Broker host | `10.0.0.211` (carpi static IP) or hostname `broker` (via dnsmasq) |
| Port | `1883` (MQTT, no TLS) |
| Authentication | None (anonymous) |
| Keep-alive | 60 seconds |
| Reconnect timeout | 5 seconds |
| Protocol version | MQTT 3.1.1 |

### ESP32 Broker Discovery

ESP32 devices obtain the broker address at runtime by reading the WiFi gateway IP after connecting to the carpi access point. This avoids hard-coding the broker address:

```c
esp_netif_get_ip_info(netif, &ip_info);
snprintf(broker_uri, sizeof(broker_uri), "mqtt://%s", inet_ntoa(ip_info.gw.addr));
```

### Android Client

The Android `pitstopper` app connects to `mqtt://broker:1883` (resolved by carpi dnsmasq) or falls back to `mqtt://10.0.0.211:1883`.

---

## Device Identifiers

Each device has a unique string ID used in status topics and client IDs.

| Device | ID | Hardware |
|--------|----|----------|
| Pit radio button box | `buttons-box` | ESP32 (`fiesta-buttons`) |
| Car telemetry unit | `telemetry` | ESP32 + ADS1115 (`fiesta-hardware`) |
| Pit window timer app | `pitstopper` | Android phone |
| OBD2 interface | `obd2` | *(future — hardware TBD)* |

---

## Topic Hierarchy

```
fiesta/
├── buttons                     # Button press/release events
├── sensors                     # Car sensor telemetry (temp, pressure)
├── obd2                        # OBD2 vehicle diagnostics (RESERVED — schema TBD)
├── pit/
│   └── window                  # Pit window state from Android
└── device/
    ├── buttons-box/
    │   └── status              # Online/offline (LWT)
    ├── telemetry/
    │   └── status              # Online/offline (LWT)
    ├── pitstopper/
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
| `button` | string | `PIT`, `FUEL`, `FCK`, `STINT`, `ALARM` |
| `state` | string | `PRESSED`, `DEPRESSED` |

**Examples:**
```json
{"button": "PIT",   "state": "PRESSED"}
{"button": "PIT",   "state": "DEPRESSED"}
{"button": "ALARM", "state": "PRESSED"}
{"button": "FUEL",  "state": "DEPRESSED"}
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
| `fiesta/obd2` | obd2 *(future)* | 0 | Yes | — |
| `fiesta/pit/window` | pitstopper | 1 | Yes | — |
| `fiesta/device/+/status` | all devices | 1 | Yes | `{"status":"offline"}` |

---

## Android Subscription Map

The `pitstopper` app subscribes to the following topics on broker connect:

| Topic | Purpose |
|-------|---------|
| `fiesta/buttons` | Display button events, trigger UI feedback |
| `fiesta/sensors` | Display live car telemetry |
| `fiesta/obd2` | *(subscribe now, handle fields when schema is defined)* |
| `fiesta/device/+/status` | Show device connection status in UI |

The app publishes to:

| Topic | When |
|-------|------|
| `fiesta/pit/window` | Pit window opens or closes |
| `fiesta/device/pitstopper/status` | On connect (`online`) and as LWT (`offline`) |

---

## Code Migration Note

The existing ESP32 firmware uses the `funkbox/` topic namespace. This must be updated to `fiesta/` to conform to this protocol.

| File | Current value | New value |
|------|--------------|-----------|
| `fiesta-buttons/main/status_broadcaster.c` | `"funkbox/buttons"` | `"fiesta/buttons"` |
| `fiesta-hardware/main/status_broadcaster.c` | `"funkbox/sensors"` | `"fiesta/sensors"` |

The Android app must be updated to subscribe to the `fiesta/` topics and to connect to the carpi broker as a client (not use the embedded Moquette broker for this purpose).
