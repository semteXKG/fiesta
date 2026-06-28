# Tire Temperature Visualizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone HTML webapp that visualizes live tire temperature heatmaps from 4 MLX90640 sensors via MQTT over WebSocket.

**Architecture:** Single HTML file loading `mqtt.js` from CDN. Each sensor's 24×32 raw matrix is bilinearly interpolated to 400×300, color-mapped with a thermal gradient, and drawn onto a Canvas in a 2×2 grid with click-to-expand single view.

**Tech Stack:** HTML5, CSS Grid, Canvas 2D, vanilla JavaScript, mqtt.js v5.x (CDN), no build step

## Global Constraints

- Single HTML file only — no server, no build step, no npm
- Default broker: `ws://10.0.0.8:9001`, overridable via `?broker=` URL param
- Output resolution: 400×300 px per tile (preserving 4:3 source aspect ratio)
- Subscribes to `fiesta/tire-temp/+/raw` wildcard topic
- Positions: FL, FR, RL, RR
- Staleness: green <2s, amber 2-5s, red >5s per tile
- Temp range defaults: 10–80°C, overridable via `?minTemp=` and `?maxTemp=`
- MQTT auto-reconnect on disconnect

---

### Task 1: HTML skeleton, CSS layout, and canvas scaffold

**Files:**
- Create: `tire-temp-viewer/tire-temp-viewer.html`

**Interfaces:**
- Produces: HTML structure with 4 canvas elements (`id="canvas-FL"` etc.), connection banner, legend placeholder, stale indicators. CSS Grid 2×2 layout. Script tag loading mqtt.js CDN and empty `<script>` stub.

- [ ] **Step 1: Write the complete HTML/CSS skeleton**

Create `tire-temp-viewer/tire-temp-viewer.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Tire Temperature Viewer</title>
<script src="https://unpkg.com/mqtt@5/dist/mqtt.min.js"></script>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { background: #1a1a2e; color: #e0e0e0; font-family: monospace; padding: 8px; }
  #banner { display: flex; justify-content: space-between; align-items: center;
            padding: 6px 12px; background: #16213e; border-radius: 4px; margin-bottom: 8px; }
  #banner.connected { border-left: 3px solid #4caf50; }
  #banner.disconnected { border-left: 3px solid #f44336; }
  #grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .tile { background: #0f0f23; border-radius: 4px; padding: 6px;
          cursor: pointer; display: flex; flex-direction: column; align-items: center; }
  .tile.expanded { grid-column: 1 / -1; }
  .tile-header { display: flex; justify-content: space-between; width: 100%;
                 padding: 2px 4px; font-size: 13px; }
  .tile-header .label { font-weight: bold; }
  .tile-header .temp { color: #888; }
  .stale-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
  .stale-dot.fresh { background: #4caf50; }
  .stale-dot.stale { background: #ff9800; }
  .stale-dot.dead { background: #f44336; }
  .tile canvas { max-width: 100%; height: auto; border-radius: 2px; }
  .tile-footer { font-size: 12px; color: #aaa; padding-top: 2px; }
  #legend { display: flex; align-items: center; gap: 8px; padding: 6px 12px;
            background: #16213e; border-radius: 4px; margin-top: 8px; font-size: 12px; }
  #legend-bar { width: 160px; height: 14px; border-radius: 2px; }
  #back-btn { display: none; cursor: pointer; background: #333; color: #e0e0e0;
              border: none; padding: 4px 12px; border-radius: 4px; font-family: monospace; }
</style>
</head>
<body>
<div id="banner" class="disconnected">
  <span><strong>Tire Temperature Viewer</strong></span>
  <span id="conn-status">disconnected</span>
  <button id="back-btn" onclick="showGrid()">Back</button>
</div>
<div id="grid">
  <div class="tile" id="tile-FL" data-pos="FL">
    <div class="tile-header"><span class="label">FL</span><span class="temp" id="ta-FL"></span><span class="stale-dot dead" id="dot-FL"></span></div>
    <canvas id="canvas-FL" width="400" height="300"></canvas>
    <div class="tile-footer">Max: <span id="max-FL">--</span>°C</div>
  </div>
  <div class="tile" id="tile-FR" data-pos="FR">
    <div class="tile-header"><span class="label">FR</span><span class="temp" id="ta-FR"></span><span class="stale-dot dead" id="dot-FR"></span></div>
    <canvas id="canvas-FR" width="400" height="300"></canvas>
    <div class="tile-footer">Max: <span id="max-FR">--</span>°C</div>
  </div>
  <div class="tile" id="tile-RL" data-pos="RL">
    <div class="tile-header"><span class="label">RL</span><span class="temp" id="ta-RL"></span><span class="stale-dot dead" id="dot-RL"></span></div>
    <canvas id="canvas-RL" width="400" height="300"></canvas>
    <div class="tile-footer">Max: <span id="max-RL">--</span>°C</div>
  </div>
  <div class="tile" id="tile-RR" data-pos="RR">
    <div class="tile-header"><span class="label">RR</span><span class="temp" id="ta-RR"></span><span class="stale-dot dead" id="dot-RR"></span></div>
    <canvas id="canvas-RR" width="400" height="300"></canvas>
    <div class="tile-footer">Max: <span id="max-RR">--</span>°C</div>
  </div>
</div>
<div id="legend">
  <span id="legend-low">10°C</span>
  <canvas id="legend-bar" width="160" height="14"></canvas>
  <span id="legend-high">80°C</span>
</div>
<script>
// -- Config --
const params = new URLSearchParams(window.location.search);
const BROKER_URL = params.get('broker') || 'ws://10.0.0.8:9001';
const MIN_TEMP = parseFloat(params.get('minTemp')) || 10;
const MAX_TEMP = parseFloat(params.get('maxTemp')) || 80;
const POSITIONS = ['FL', 'FR', 'RL', 'RR'];

// -- State --
let lastUpdate = { FL: 0, FR: 0, RL: 0, RR: 0 };
let lastData = {};
let expandedPos = null;
</script>
</body>
</html>
```

- [ ] **Step 2: Verify the file exists and has correct structure**

```bash
wc -l tire-temp-viewer/tire-temp-viewer.html
```

Expected: ~75 lines.

- [ ] **Step 3: Commit**

```bash
git add tire-temp-viewer/tire-temp-viewer.html
git commit -m "feat: add HTML/CSS skeleton with 2×2 canvas grid"
```

---

### Task 2: Bilinear interpolation and thermal colormap

**Files:**
- Modify: `tire-temp-viewer/tire-temp-viewer.html` — add utility functions in `<script>` after state declarations

**Interfaces:**
- Consumes: `MIN_TEMP`, `MAX_TEMP` globals from Task 1
- Produces:
  - `bilinearInterpolate(pixels24x32) → Float32Array[300][400]`
  - `buildColormap() → Uint8ClampedArray[1024]` (256 RGBA entries, called once at init)
  - `mapToRGBA(data300x400, colormap) → Uint8ClampedArray[400*300*4]`
  - `drawLegend(colormap)` — renders the legend bar canvas

- [ ] **Step 1: Add bilinearInterpolate function**

Insert after `let expandedPos = null;`:

```javascript
function bilinearInterpolate(pixels) {
  const srcRows = 24, srcCols = 32;
  const outRows = 300, outCols = 400;
  const result = new Float32Array(outRows * outCols);

  for (let oy = 0; oy < outRows; oy++) {
    const sy = oy * (srcRows - 1) / (outRows - 1);
    const i0 = Math.floor(sy);
    const i1 = Math.min(i0 + 1, srcRows - 1);
    const fy = sy - i0;

    for (let ox = 0; ox < outCols; ox++) {
      const sx = ox * (srcCols - 1) / (outCols - 1);
      const j0 = Math.floor(sx);
      const j1 = Math.min(j0 + 1, srcCols - 1);
      const fx = sx - j0;

      result[oy * outCols + ox] =
        pixels[i0][j0] * (1 - fy) * (1 - fx) +
        pixels[i1][j0] * fy * (1 - fx) +
        pixels[i0][j1] * (1 - fy) * fx +
        pixels[i1][j1] * fy * fx;
    }
  }

  return result;
}
```

- [ ] **Step 2: Add buildColormap function**

Insert after bilinearInterpolate:

```javascript
function buildColormap() {
  const lut = new Uint8ClampedArray(256 * 4);
  for (let i = 0; i < 256; i++) {
    const t = i / 255;
    let r, g, b;
    if (t < 0.25)      { r = 0;           g = 0;            b = Math.round(255 * (t / 0.25 + 0.25)); }
    else if (t < 0.5)  { r = 0;           g = Math.round(255 * ((t - 0.25) / 0.25)); b = 255; }
    else if (t < 0.75) { r = Math.round(255 * ((t - 0.5) / 0.25));  g = 255;          b = Math.round(255 * (1 - (t - 0.5) / 0.25)); }
    else               { r = 255;         g = Math.round(255 * (1 - (t - 0.75) / 0.25)); b = 0; }

    lut[i * 4]     = r;
    lut[i * 4 + 1] = g;
    lut[i * 4 + 2] = b;
    lut[i * 4 + 3] = 255;
  }
  return lut;
}
```

- [ ] **Step 3: Add mapToRGBA function**

Insert after buildColormap:

```javascript
function mapToRGBA(data, colormap) {
  const len = data.length;
  const rgba = new Uint8ClampedArray(len * 4);
  const range = MAX_TEMP - MIN_TEMP;

  for (let i = 0; i < len; i++) {
    let v = data[i];
    if (isNaN(v)) v = MIN_TEMP;
    v = Math.max(MIN_TEMP, Math.min(MAX_TEMP, v));
    const idx = Math.round(((v - MIN_TEMP) / range) * 255) * 4;
    rgba[i * 4]     = colormap[idx];
    rgba[i * 4 + 1] = colormap[idx + 1];
    rgba[i * 4 + 2] = colormap[idx + 2];
    rgba[i * 4 + 3] = 255;
  }
  return rgba;
}
```

- [ ] **Step 4: Add drawLegend function**

Insert after mapToRGBA:

```javascript
function drawLegend() {
  const canvas = document.getElementById('legend-bar');
  const ctx = canvas.getContext('2d');
  const imageData = ctx.createImageData(160, 14);
  for (let x = 0; x < 160; x++) {
    const t = x / 159;
    const idx = Math.round(t * 255) * 4;
    for (let y = 0; y < 14; y++) {
      const p = (y * 160 + x) * 4;
      imageData.data[p]     = colormap[idx];
      imageData.data[p + 1] = colormap[idx + 1];
      imageData.data[p + 2] = colormap[idx + 2];
      imageData.data[p + 3] = 255;
    }
  }
  ctx.putImageData(imageData, 0, 0);
}
```

- [ ] **Step 5: Verify by opening the file in a browser**

```bash
ls -la tire-temp-viewer/tire-temp-viewer.html
```

- [ ] **Step 6: Commit**

```bash
git add tire-temp-viewer/tire-temp-viewer.html
git commit -m "feat: add bilinear interpolation and thermal colormap"
```

---

### Task 3: MQTT connection manager

**Files:**
- Modify: `tire-temp-viewer/tire-temp-viewer.html` — add MQTT code in `<script>`

**Interfaces:**
- Consumes: `BROKER_URL`, `POSITIONS` globals; `lastUpdate`, `lastData` state objects
- Produces:
  - `connectMqtt()` — connects, subscribes, sets up reconnect, calls `onMessage(pos, data)`
  - `updateStaleness()` — called on a 2s interval, updates dot classes and indicator
  - `onMessage(pos, data)` — stub that stores data in `lastData[pos]` and updates `lastUpdate[pos]`

- [ ] **Step 1: Add MQTT connection and staleness functions**

Insert after the last utility function (after `drawLegend`):

```javascript
let mqttClient = null;

function connectMqtt() {
  const banner = document.getElementById('banner');
  const status = document.getElementById('conn-status');

  status.textContent = 'connecting...';
  banner.className = 'disconnected';

  mqttClient = mqtt.connect(BROKER_URL, {
    reconnectPeriod: 3000,
    connectTimeout: 10000,
  });

  mqttClient.on('connect', () => {
    status.textContent = 'connected';
    banner.className = 'connected';
    mqttClient.subscribe('fiesta/tire-temp/+/raw', { qos: 1 }, (err) => {
      if (err) console.error('subscribe error:', err);
    });
  });

  mqttClient.on('message', (topic, payload) => {
    const pos = topic.split('/')[2]; // extract FL/FR/RL/RR
    if (!POSITIONS.includes(pos)) return;

    try {
      const msg = JSON.parse(payload.toString());
      if (!msg.pixels || !Array.isArray(msg.pixels) || msg.pixels.length !== 24) {
        console.warn('bad pixel array from', pos);
        return;
      }
      if (msg.pixels.some(row => !Array.isArray(row) || row.length !== 32)) {
        console.warn('bad row length from', pos);
        return;
      }

      lastData[pos] = msg;
      lastUpdate[pos] = Date.now();
      onMessage(pos, msg);
    } catch (e) {
      console.error('parse error:', e);
    }
  });

  mqttClient.on('error', (e) => {
    console.error('mqtt error:', e);
    status.textContent = 'error';
    banner.className = 'disconnected';
  });

  mqttClient.on('close', () => {
    status.textContent = 'disconnected';
    banner.className = 'disconnected';
  });
}

function updateStaleness() {
  const now = Date.now();
  for (const pos of POSITIONS) {
    const age = now - lastUpdate[pos];
    const isInit = lastUpdate[pos] === 0;
    const dot = document.getElementById('dot-' + pos);
    dot.className = 'stale-dot ' + (isInit ? 'dead' : age < 2000 ? 'fresh' : age < 5000 ? 'stale' : 'dead');
  }
}

// Stub — will be extended in Task 4
function onMessage(pos, msg) {
  // placeholder, replaced by Task 4
}
```

- [ ] **Step 2: Verify function signatures match Task 4 expectations**

`onMessage(pos, msg)` signature matches — `pos` is string (FL/FR/RL/RR), `msg` is `{ts, ta, pixels[24][32]}`.

- [ ] **Step 3: Commit**

```bash
git add tire-temp-viewer/tire-temp-viewer.html
git commit -m "feat: add MQTT connection manager and staleness tracking"
```

---

### Task 4: Heatmap renderer

**Files:**
- Modify: `tire-temp-viewer/tire-temp-viewer.html` — expand `onMessage()` and add render function

**Interfaces:**
- Consumes: `bilinearInterpolate()`, `mapToRGBA()`, `colormap` global, `POSITIONS`, `lastData`
- Produces: `renderTile(pos)` — interpolates, maps to RGBA, draws on canvas, updates UI labels

- [ ] **Step 1: Add colormap init and renderTile function**

Replace the `onMessage` stub:

```javascript
// -- Colormap (built once) --
const colormap = buildColormap();
drawLegend();

function renderTile(pos) {
  const data = lastData[pos];
  if (!data) return;

  const canvas = document.getElementById('canvas-' + pos);
  const ctx = canvas.getContext('2d');

  // Interpolate
  const interpolated = bilinearInterpolate(data.pixels);

  // Find max
  let max = -Infinity;
  for (let i = 0; i < interpolated.length; i++) {
    if (interpolated[i] > max) max = interpolated[i];
  }

  // Color-map
  const rgba = mapToRGBA(interpolated, colormap);

  // Draw
  const imageData = new ImageData(rgba, 400, 300);
  ctx.putImageData(imageData, 0, 0);

  // Update UI
  document.getElementById('ta-' + pos).textContent = 'ta: ' + data.ta.toFixed(1) + '°C';
  document.getElementById('max-' + pos).textContent = max.toFixed(1);
}

function onMessage(pos, msg) {
  renderTile(pos);
}
```

- [ ] **Step 2: Verify canvas rendering works**

Open the file in a browser pointed at a running MQTT broker publishing to `fiesta/tire-temp/+/raw`. Each tile should update with interpolated heatmaps.

- [ ] **Step 3: Commit**

```bash
git add tire-temp-viewer/tire-temp-viewer.html
git commit -m "feat: add heatmap renderer with interpolation and color-mapping"
```

---

### Task 5: Grid controller — click-to-expand and staleness timer

**Files:**
- Modify: `tire-temp-viewer/tire-temp-viewer.html` — add click handlers, expand/shrink logic, staleness interval

**Interfaces:**
- Consumes: `POSITIONS`, `expandedPos` global, `.tile` elements, `#back-btn`
- Produces: `showTile(pos)` — expands one tile, hides others; `showGrid()` — restores 2×2; click handlers on `.tile` elements; `setInterval(updateStaleness, 2000)`

- [ ] **Step 1: Add expand/shrink functions and event wiring**

Insert at the bottom of the `<script>` block, before the closing `</script>`:

```javascript
// -- Grid expand/collapse --
function showTile(pos) {
  expandedPos = pos;
  for (const p of POSITIONS) {
    const tile = document.getElementById('tile-' + p);
    tile.style.display = p === pos ? '' : 'none';
    if (p === pos) tile.classList.add('expanded');
  }
  document.getElementById('back-btn').style.display = '';
  document.getElementById('legend').style.display = '';
}

function showGrid() {
  expandedPos = null;
  for (const p of POSITIONS) {
    const tile = document.getElementById('tile-' + p);
    tile.style.display = '';
    tile.classList.remove('expanded');
  }
  document.getElementById('back-btn').style.display = 'none';
  document.getElementById('legend').style.display = '';
}

// Wire click handlers to tiles
for (const pos of POSITIONS) {
  document.getElementById('tile-' + pos).addEventListener('click', (e) => {
    if (!expandedPos) showTile(pos);
  });
}
```

- [ ] **Step 2: Add staleness check interval and startup**

Insert after the click handlers:

```javascript
// -- Staleness check every 2 seconds --
setInterval(updateStaleness, 2000);

// -- Startup --
connectMqtt();
```

- [ ] **Step 3: Verify click-to-expand behavior**

Open in browser. Click a tile — only that tile should be visible. Click "Back" — returns to 2×2 grid.

- [ ] **Step 4: Commit**

```bash
git add tire-temp-viewer/tire-temp-viewer.html
git commit -m "feat: add click-to-expand grid and staleness timer"
```

---

### Task 6: Integration, error handling polish, and final verification

**Files:**
- Modify: `tire-temp-viewer/tire-temp-viewer.html` — reinforce error handling, add initial dimmed canvas state, legend labels

**Interfaces:**
- Consumes: All prior components
- Produces: Final working application, cold-start empty canvases, NaN handling verification

- [ ] **Step 1: Add initial dimmed canvas rendering**

Insert before `// -- Startup --`:

```javascript
// -- Paint initial empty (dimmed grey) canvases --
for (const pos of POSITIONS) {
  const canvas = document.getElementById('canvas-' + pos);
  const ctx = canvas.getContext('2d');
  const imageData = ctx.createImageData(400, 300);
  for (let i = 0; i < imageData.data.length; i += 4) {
    imageData.data[i]     = 20;
    imageData.data[i + 1] = 20;
    imageData.data[i + 2] = 30;
    imageData.data[i + 3] = 255;
  }
  ctx.putImageData(imageData, 0, 0);
}
```

- [ ] **Step 2: Update legend labels from config**

Insert after `drawLegend()` call:

```javascript
document.getElementById('legend-low').textContent = MIN_TEMP + '°C';
document.getElementById('legend-high').textContent = MAX_TEMP + '°C';
```

- [ ] **Step 3: Verify complete file structure and line count**

```bash
wc -l tire-temp-viewer/tire-temp-viewer.html
```

- [ ] **Step 4: Manual verification checklist**

Open the file in a browser with a query like `?broker=ws://10.0.0.8:9001`:
1. Banner shows "connected" when broker is reachable
2. 2×2 grid with 4 tiles, each 400×300 canvas
3. Heatmaps update as MQTT data arrives
4. Max temp and ambient temp labels update
5. Staleness dots change color based on message age
6. Click a tile → expands to full width, others hidden
7. "Back" button visible in expanded mode, returns to grid
8. Color scale legend shows correct min/max
9. Auto-reconnect on broker restart

- [ ] **Step 5: Commit**

```bash
git add tire-temp-viewer/tire-temp-viewer.html
git commit -m "feat: add cold-start canvases, legend labels, final polish"
```

---

## Self-Review

**1. Spec coverage:**
- MQTT over WebSocket (`connectMqtt`) — Task 3
- Subscribe `fiesta/tire-temp/+/raw` — Task 3
- Bilinear interpolation 24×32 → 400×300 — Task 2
- Thermal colormap — Task 2
- 2×2 Canvas grid — Task 1 (HTML) + Task 4 (rendering)
- Click-to-expand — Task 5
- Staleness indicators — Task 3 + Task 5
- Connection banner — Task 1 (HTML) + Task 3 (state)
- Color scale legend — Task 2 + Task 6
- URL config (`broker`, `minTemp`, `maxTemp`) — Task 1
- Auto-reconnect — Task 3 (mqtt.js built-in `reconnectPeriod`)
- NaN/inf handling — Task 2 (clamped in `mapToRGBA`)
- Malformed JSON — Task 3 (try/catch, logged)
- Wrong pixel dimensions — Task 3 (validated in message handler)

**2. Placeholder scan:** No TBDs, TODOs, or vague instructions. All functions have complete code.

**3. Type consistency:**
- `onMessage(pos, msg)` defined as stub in Task 3, replaced with full impl in Task 4 — consistent signature
- `colormap` built in Task 2, consumed in Task 4 — consistent
- `lastUpdate`, `lastData` initialized in Task 1, used in Tasks 3, 4, 5 — consistent
- `POSITIONS` array initialized in Task 1, used throughout — consistent
- All canvas IDs match: `canvas-FL`, `canvas-FR`, `canvas-RL`, `canvas-RR`
- All element IDs match across HTML and JS: `ta-{pos}`, `max-{pos}`, `dot-{pos}`, `tile-{pos}`
