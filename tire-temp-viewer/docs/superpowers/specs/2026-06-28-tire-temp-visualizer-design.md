# Tire Temperature Visualizer — Design Spec

## Overview

A standalone HTML webapp that visualizes tire temperature data from all four MLX90640
sensors (FL, FR, RL, RR). Connects directly to an Eclipse Mosquitto MQTT broker via
WebSockets, reads the `fiesta/tire-temp/{pos}/raw` topics, upsamples the 24×32 raw
matrix to 400×300 pixels (preserving 4:3 aspect ratio) using bilinear interpolation, and renders thermal heatmaps
in a 2×2 grid with click-to-expand single-view.

---

## Protocol (source: `PROTOCOL.md`)

| Topic | Publisher | Payload |
|-------|-----------|---------|
| `fiesta/tire-temp/{FL,FR,RL,RR}/raw` | `tire-temp-{pos}` | `{"ts": uint32, "ta": float, "pixels": float[24][32]}` |

- **Rate:** ~1 Hz per position
- **QoS:** 1, no retain
- **pixels:** 24 rows × 32 columns, row 0 = top of sensor FOV, values in °C

---

## Technology

**Single HTML file** — no build step, no server, no dependencies beyond CDN-hosted libraries.

| Dependency | Source | Purpose |
|-----------|--------|---------|
| `mqtt.js` (v5.x) | `unpkg.com/mqtt` | MQTT over WebSocket client |
| No framework | — | Vanilla JS, Canvas 2D, CSS Grid |

---

## Architecture

```
MQTT Broker (Mosquitto, WebSocket listener)
    │
    │  MQTT over WSS (mqtt.js)
    ▼
┌─────────────────────────────────────────────┐
│  Single HTML file (tire-temp-viewer.html)    │
│                                              │
│  MqttManager                                 │
│  ├─ Connect to broker                        │
│  ├─ Subscribe fiesta/tire-temp/+/raw         │
│  ├─ Parse JSON per message                   │
│  ├─ Emit events: {pos, ts, ta, pixels[24][32]}│
│  └─ Track last-update wall-clock per position │
│                                              │
│  ThermalInterpolator                         │
│  ├─ Input:  float[24][32]                    │
│  ├─ Output: float[300][400]                  │
│  └─ Method: bilinear interpolation           │
│                                              │
│  ColorMapper                                 │
│  ├─ Input:  float[300][400]                  │
│  ├─ Output: Uint8ClampedArray (RGBA)         │
│  └─ Method: thermal gradient LUT             │
│                                              │
│  HeatmapRenderer                             │
│  ├─ Draw ImageData onto <canvas>             │
│  └─ Per-tile canvas, size: 400×300 px        │
│                                              │
│  GridController                              │
│  ├─ CSS Grid 2×2 layout                      │
│  ├─ Click tile → expand single view          │
│  └─ Back button → return to grid             │
│                                              │
│  UI Layer                                    │
│  ├─ Position labels (FL/FR/RL/RR)            │
│  ├─ Max temp per tile                        │
│  ├─ Staleness indicator per tile             │
│  ├─ Color scale legend                       │
│  └─ Connection status banner                 │
└─────────────────────────────────────────────┘
```

---

## Components (detail)

### MqttManager

- Reads broker URL from `?broker=ws://host:port` query param (default: `ws://10.0.0.8:9001`)
- Subscribes to `fiesta/tire-temp/+/raw` wildcard topic
- On message: parses JSON, extracts position from topic string, emits `{pos, ts, ta, pixels}`
- Tracks `lastSeen` per position using `Date.now()` on each message
- Emits `stale` event every 2s with per-position ages

### ThermalInterpolator

- Bilinear interpolation: for each output pixel (x, y), compute source coordinates
  `sx = x * 31/399`, `sy = y * 23/299`
- Sample the four surrounding source pixels weighted by fractional distance
- Edge pixels: clamp to nearest boundary (no extrapolation)
- Runs synchronously per message (4×300×400 at 1 Hz is negligible CPU)

### ColorMapper

- Thermal colormap LUT: 256 RGB entries from blue (cold) → cyan → green → yellow → red (hot)
- Input range: configurable `minTemp` / `maxTemp` defaults (e.g. 10°C / 80°C)
- Clamps values outside range, scales to 0-255 LUT index
- Generates `ImageData` (RGBA) directly for `canvas.putImageData()`
- A = 255 always (fully opaque)

### HeatmapRenderer

- One per tile. Owns a `<canvas>` element sized 400×300 (CSS can scale for display)
- Accepts `pixels[24][32]` → interpolate → color-map → draw
- Also renders: position label, ambient temp, max temp
- State: `position`, `data`, `lastUpdate`

### GridController

- 2×2 CSS Grid with equal-sized tiles
- Each tile = `HeatmapRenderer` instance
- Click handler: toggle between grid view and expanded single-tile view
- Expanded view: selected canvas scales up with the color legend and detail readout
- "Back" / "Show all" button in expanded view

### UI Layer

- **Connection banner:** Shows broker URL, connection state (connected/disconnected)
- **Staleness indicators:** Small dot per tile — green <2s, amber 2-5s, red >5s
- **Color scale legend:** Vertical gradient bar with °C ticks
- **Ambient temp:** Per-tile `ta` value shown next to position label
- **Max temp:** Per-tile maximum from the interpolated matrix

---

## Layout

```
┌──────────────────────────────────────────────┐
│  Tire Temperature Viewer              [⚡ ws://...] │
├──────────────────────┬───────────────────────┤
│  FL  ta: 22.5°C │ ●  │  FR  ta: 22.6°C │ ●  │
│  ┌──────────────────────┐  │  ┌──────────────────────┐ │
│  │                      │  │  │                      │ │
│  │       heatmap        │  │  │       heatmap        │ │
│  │      400×300         │  │  │      400×300         │ │
│  │                      │  │  │                      │ │
│  └──────────────────────┘  │  └──────────────────────┘ │
│  Max: 52.3°C               │  Max: 48.1°C              │
├────────────────────────────┼───────────────────────────┤
│  RL  ta: 22.4°C  │  ●      │  RR  ta: 22.5°C  │  ●    │
│  ┌──────────────────────┐  │  ┌──────────────────────┐ │
│  │                      │  │  │                      │ │
│  │       heatmap        │  │  │       heatmap        │ │
│  │                      │  │  │                      │ │
│  └──────────────────────┘  │  └──────────────────────┘ │
│  Max: 45.2°C         │  Max: 50.7°C          │
├──────────────────────┴───────────────────────┤
│  ▌ 10°C                  ▌ 80°C              │  ← color scale legend
└──────────────────────────────────────────────┘
```

Expanded view: one tile fills the main area at higher CSS resolution, color scale beside it, back button at top.

---

## Interpolation method

Bilinear interpolation between source grid points:

```
For output pixel (ox, oy) in [0,299] × [0,399]:
  sx = ox * (23 / 299)      → fractional row in source
  sy = oy * (31 / 399)      → fractional col in source
  i0 = floor(sx), i1 = min(i0+1, 23)
  j0 = floor(sy), j1 = min(j0+1, 31)
  fx = sx - i0              → fractional part row
  fy = sy - j0              → fractional part col
  
  result = pixels[i0][j0] * (1-fx)*(1-fy)
         + pixels[i1][j0] * fx*(1-fy)
         + pixels[i0][j1] * (1-fx)*fy
         + pixels[i1][j1] * fx*fy
```

---

## Error handling

- **MQTT disconnect:** Banner turns red, tiles show last-known data dimmed, auto-reconnect with backoff
- **Malformed JSON:** Log to console, skip message, no crash
- **Missing position in topic:** Log warning, skip
- **Wrong pixel dimensions:** Skip message if array is not 24×32
- **NaN/inf values:** Clamp to renderable range, show as a distinct color (e.g. dark grey)

---

## Configuration

All via URL query parameters (no config file needed):

| Param | Default | Description |
|-------|---------|-------------|
| `broker` | `ws://10.0.0.8:9001` | MQTT WebSocket URL |
| `minTemp` | `10` | Minimum °C for color scale |
| `maxTemp` | `80` | Maximum °C for color scale |

---

## File structure

```
tire-temp-viewer/
├── tire-temp-viewer.html    ← the entire application
└── docs/superpowers/specs/
    └── 2026-06-28-tire-temp-visualizer-design.md
```

---

## Out of scope

- Recording / playback of historical data
- Statistical analysis (min/mean/stddev per zone)
- MQTT authentication beyond what the URL supports
- Mobile-optimized responsive layout (works on desktop first)
- Comparison / diff view between tires
