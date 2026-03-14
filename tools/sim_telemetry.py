#!/usr/bin/env python3
"""
Fiesta MQTT Telemetry Simulator

Simulates realistic Ford Fiesta ST race car telemetry and publishes
to MQTT at 10 Hz, matching the protocol in PROTOCOL.md.

Usage:
    python sim_telemetry.py [--broker HOST] [--port PORT]

Default broker: 192.168.4.1:1883 (carpi on fiesta-network, anonymous)

Driving phases cycle automatically:
    STRAIGHT → BRAKING → CORNER → ACCELERATION → STRAIGHT → ...
    (with occasional PIT_LANE visits)
"""

import argparse
import json
import math
import random
import signal
import sys
import time
from enum import Enum, auto

import paho.mqtt.client as mqtt

# ── MQTT Topics ──────────────────────────────────────────────────────

TOPIC_CAN_201 = "fiesta/can/201"      # rpm, speed_kmh, throttle_pct
TOPIC_CAN_360 = "fiesta/can/360"      # brake_pedal
TOPIC_CAN_420 = "fiesta/can/420"      # coolant_c, brake_pedal
TOPIC_CAN_428 = "fiesta/can/428"      # battery_v
TOPIC_SENSORS = "fiesta/sensors"      # oil_temp, oil_pres, water_temp, gas_pres

TICK_HZ = 10
TICK_S = 1.0 / TICK_HZ

# ── Driving Phases ───────────────────────────────────────────────────

class Phase(Enum):
    STRAIGHT = auto()
    BRAKING = auto()
    CORNER = auto()
    ACCELERATION = auto()
    PIT_LANE = auto()


# Phase targets: (rpm, speed_kmh, throttle_pct)
# Actual values slew toward these at rate-limited speed
PHASE_TARGETS = {
    Phase.STRAIGHT:     {"rpm": 6500, "speed": 165, "throttle": 100},
    Phase.BRAKING:      {"rpm": 2200, "speed": 70,  "throttle": 0},
    Phase.CORNER:       {"rpm": 3800, "speed": 85,  "throttle": 30},
    Phase.ACCELERATION: {"rpm": 5500, "speed": 130, "throttle": 85},
    Phase.PIT_LANE:     {"rpm": 1800, "speed": 38,  "throttle": 15},
}

# Typical duration in seconds for each phase (randomized ±30%)
PHASE_DURATION = {
    Phase.STRAIGHT:     4.0,
    Phase.BRAKING:      2.0,
    Phase.CORNER:       3.0,
    Phase.ACCELERATION: 3.5,
    Phase.PIT_LANE:     8.0,
}

# Normal lap phase cycle
NORMAL_CYCLE = [Phase.STRAIGHT, Phase.BRAKING, Phase.CORNER, Phase.ACCELERATION]


# ── Rate Limits (max change per 100ms tick) ──────────────────────────

RATE = {
    "rpm":       500,
    "speed":     5.0,
    "throttle":  20.0,
    "coolant":   0.1,
    "oil_temp":  0.15,
    "oil_pres":  0.3,
    "battery":   0.05,
}

# ── Value Ranges ─────────────────────────────────────────────────────

RANGE = {
    "rpm":       (800,  7000),
    "speed":     (0.0,  180.0),
    "throttle":  (0.0,  100.0),
    "coolant":   (75.0, 115.0),
    "oil_temp":  (75.0, 130.0),
    "oil_pres":  (1.0,  5.0),
    "battery":   (11.5, 14.8),
}


# ── Helpers ──────────────────────────────────────────────────────────

def clamp(value, lo, hi):
    return max(lo, min(hi, value))


def slew(current, target, max_delta):
    """Move current toward target by at most max_delta."""
    diff = target - current
    if abs(diff) <= max_delta:
        return target
    return current + max_delta * (1 if diff > 0 else -1)


def jitter(value, amount):
    """Add small random noise."""
    return value + random.uniform(-amount, amount)


# ── Car State ────────────────────────────────────────────────────────

class CarState:
    def __init__(self):
        # Primary (fast-changing)
        self.rpm = 850.0
        self.speed = 0.0
        self.throttle = 0.0
        self.brake = "off"

        # Thermal (slow-changing)
        self.coolant = 82.0
        self.oil_temp = 80.0
        self.oil_pres = 3.2
        self.battery = 12.8

        # Phase management
        self.phase = Phase.STRAIGHT
        self.phase_timer = 0.0
        self.phase_duration = self._pick_duration()
        self.cycle_index = 0
        self.laps_until_pit = random.randint(8, 15)
        self.lap_count = 0

    def _pick_duration(self):
        base = PHASE_DURATION[self.phase]
        return base * random.uniform(0.7, 1.3)

    def _next_phase(self):
        if self.phase == Phase.PIT_LANE:
            self.cycle_index = 0
            self.laps_until_pit = random.randint(8, 15)
            self.lap_count = 0
            self.phase = NORMAL_CYCLE[0]
        else:
            # Track lap completion (one full cycle = one lap)
            old_index = self.cycle_index
            self.cycle_index = (self.cycle_index + 1) % len(NORMAL_CYCLE)
            if self.cycle_index == 0:
                self.lap_count += 1

            # Occasionally visit pit lane
            if self.lap_count >= self.laps_until_pit and self.cycle_index == 0:
                self.phase = Phase.PIT_LANE
            else:
                self.phase = NORMAL_CYCLE[self.cycle_index]

        self.phase_timer = 0.0
        self.phase_duration = self._pick_duration()

    def tick(self):
        """Advance simulation by one tick (100ms)."""
        targets = PHASE_TARGETS[self.phase]

        # ── Primary values: slew toward phase targets ──
        target_rpm = targets["rpm"] + random.uniform(-200, 200)
        target_speed = targets["speed"] + random.uniform(-3, 3)
        target_throttle = targets["throttle"] + random.uniform(-5, 5)

        self.rpm = slew(self.rpm, target_rpm, RATE["rpm"])
        self.speed = slew(self.speed, target_speed, RATE["speed"])
        self.throttle = slew(self.throttle, target_throttle, RATE["throttle"])

        # Clamp to valid ranges
        self.rpm = clamp(self.rpm, *RANGE["rpm"])
        self.speed = clamp(self.speed, *RANGE["speed"])
        self.throttle = clamp(self.throttle, *RANGE["throttle"])

        # ── Brake state ──
        if self.phase == Phase.BRAKING:
            self.brake = "pressed"
        elif self.phase == Phase.CORNER and self.phase_timer < 0.5:
            self.brake = "touch"
        else:
            self.brake = "off"

        # ── Thermal model ──
        # Coolant: rises under load (high RPM), cools slowly otherwise
        load_factor = self.rpm / 7000.0
        coolant_target = 85 + load_factor * 20  # 85°C idle → 105°C full load
        coolant_target += random.uniform(-0.3, 0.3)
        self.coolant = slew(self.coolant, coolant_target, RATE["coolant"])
        self.coolant = clamp(self.coolant, *RANGE["coolant"])

        # Oil temp: similar to coolant but hotter and slower
        oil_target = 90 + load_factor * 25  # 90°C idle → 115°C full load
        oil_target += random.uniform(-0.3, 0.3)
        self.oil_temp = slew(self.oil_temp, oil_target, RATE["oil_temp"])
        self.oil_temp = clamp(self.oil_temp, *RANGE["oil_temp"])

        # Oil pressure: correlates with RPM
        pres_target = 1.5 + (self.rpm / 7000.0) * 3.0  # 1.5 bar idle → 4.5 bar redline
        pres_target += random.uniform(-0.1, 0.1)
        self.oil_pres = slew(self.oil_pres, pres_target, RATE["oil_pres"])
        self.oil_pres = clamp(self.oil_pres, *RANGE["oil_pres"])

        # Battery: alternator charges above ~1500 RPM
        if self.rpm > 1500:
            batt_target = 14.1 + random.uniform(-0.15, 0.15)
        else:
            batt_target = 12.6 + random.uniform(-0.1, 0.1)
        self.battery = slew(self.battery, batt_target, RATE["battery"])
        self.battery = clamp(self.battery, *RANGE["battery"])

        # ── Phase timer ──
        self.phase_timer += TICK_S
        if self.phase_timer >= self.phase_duration:
            self._next_phase()

    def to_can_201(self):
        return {
            "rpm": round(self.rpm),
            "speed_kmh": round(self.speed, 2),
            "throttle_pct": round(self.throttle, 1),
        }

    def to_can_360(self):
        return {"brake_pedal": self.brake}

    def to_can_420(self):
        return {
            "coolant_c": round(self.coolant),
            "brake_pedal": self.brake,
        }

    def to_can_428(self):
        return {"battery_v": round(self.battery, 1)}

    def to_sensors(self):
        return {
            "oil_temp": round(self.oil_temp),
            "oil_pres": round(self.oil_pres, 2),
            "water_temp": round(self.coolant),
            "gas_pres": round(random.uniform(2.8, 3.2), 2),
        }


# ── MQTT ─────────────────────────────────────────────────────────────

def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        print("✓ Connected to MQTT broker")
    else:
        print(f"✗ Connection failed (rc={rc})")


def on_disconnect(client, userdata, flags, rc, properties=None):
    if rc != 0:
        print(f"  Disconnected unexpectedly (rc={rc}), reconnecting...")


# ── Main Loop ────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Fiesta telemetry simulator")
    parser.add_argument("--broker", default="192.168.4.1", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    args = parser.parse_args()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="sim-telemetry", clean_session=True)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect

    print(f"Connecting to {args.broker}:{args.port} ...")
    try:
        client.connect(args.broker, args.port, keepalive=60)
    except Exception as e:
        print(f"✗ Cannot connect: {e}")
        sys.exit(1)

    client.loop_start()

    car = CarState()
    running = True

    def stop(sig, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    tick_count = 0
    print(f"Publishing at {TICK_HZ} Hz — Ctrl+C to stop\n")

    try:
        while running:
            t0 = time.monotonic()

            car.tick()
            tick_count += 1

            # Publish CAN 201 every tick (10 Hz)
            client.publish(TOPIC_CAN_201, json.dumps(car.to_can_201()), qos=0)

            # Publish CAN 360 every tick
            client.publish(TOPIC_CAN_360, json.dumps(car.to_can_360()), qos=0)

            # Publish CAN 420 every tick
            client.publish(TOPIC_CAN_420, json.dumps(car.to_can_420()), qos=0)

            # Publish CAN 428 every tick
            client.publish(TOPIC_CAN_428, json.dumps(car.to_can_428()), qos=0)

            # Publish sensors every 5th tick (2 Hz — ADC is slower)
            if tick_count % 5 == 0:
                client.publish(TOPIC_SENSORS, json.dumps(car.to_sensors()), qos=0, retain=True)

            # Status line every second
            if tick_count % TICK_HZ == 0:
                phase_name = car.phase.name.ljust(13)
                print(
                    f"  {phase_name} "
                    f"RPM {car.rpm:5.0f}  "
                    f"SPD {car.speed:5.1f}  "
                    f"THR {car.throttle:4.0f}%  "
                    f"BRK {car.brake:7s}  "
                    f"COOL {car.coolant:4.0f}°  "
                    f"OIL {car.oil_temp:4.0f}°/{car.oil_pres:.1f}b  "
                    f"BATT {car.battery:.1f}V",
                    end="\r",
                )

            # Precise tick timing
            elapsed = time.monotonic() - t0
            sleep_time = TICK_S - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

    except KeyboardInterrupt:
        pass

    print("\n\nShutting down...")
    client.loop_stop()
    client.disconnect()
    print("Done.")


if __name__ == "__main__":
    main()
