#!/usr/bin/env python3
"""
Fiesta MQTT chat test client.
Speaks the fiesta/chat protocol defined in PROTOCOL.md.
"""

import json
import sys
import threading
import paho.mqtt.client as mqtt

DEFAULT_HOST      = "broker.hivemq.com"
DEFAULT_PORT      = 1883
DEFAULT_DEVICE_ID = "pitcrew-1"


def prompt(label, default):
    value = input(f"{label} [{default}]: ").strip()
    return value if value else default


def main():
    print("=== Fiesta Chat Client ===\n")
    host      = prompt("Broker host", DEFAULT_HOST)
    port      = int(prompt("Broker port", str(DEFAULT_PORT)))
    session   = input("Session ID   [blank = local, no prefix]: ").strip()
    device_id = prompt("Device ID (sender)", DEFAULT_DEVICE_ID)

    prefix      = session + "/" if session else ""
    chat_topic  = prefix + "fiesta/chat"
    status_topic = prefix + "fiesta/device/" + device_id + "/status"

    print(f"\nConnecting to {host}:{port} as '{device_id}'")
    print(f"Topic: {chat_topic}\n")

    client = mqtt.Client(client_id=device_id, clean_session=True)

    # Last Will and Testament
    client.will_set(status_topic, json.dumps({"status": "offline"}), qos=1, retain=True)

    def on_connect(c, userdata, flags, rc):
        if rc != 0:
            print(f"[error] Connection failed (rc={rc})")
            return
        c.subscribe(chat_topic, qos=1)
        c.publish(status_topic, json.dumps({"status": "online"}), qos=1, retain=True)
        print("[connected] Type a message and press Enter. Type 'quit' to exit.\n")

    def on_message(c, userdata, msg):
        try:
            data = json.loads(msg.payload.decode())
            frm  = data.get("from", "?")
            text = data.get("text", "")
            flags = []
            if data.get("isAlert"):        flags.append("ALERT")
            if data.get("isNotification"): flags.append("NOTIFICATION")
            tag = f"[{', '.join(flags)}] " if flags else ""
            print(f"\r[{frm}] {tag}{text}")
            # Reprint the input prompt on the same line
            print("> ", end="", flush=True)
        except Exception:
            print(f"\r[raw] {msg.payload!r}")
            print("> ", end="", flush=True)

    def on_disconnect(c, userdata, rc):
        if rc != 0:
            print("\n[disconnected unexpectedly]")

    client.on_connect    = on_connect
    client.on_message    = on_message
    client.on_disconnect = on_disconnect

    client.connect(host, port, keepalive=60)
    client.loop_start()

    try:
        while True:
            print("> ", end="", flush=True)
            line = input()
            if line.strip().lower() == "quit":
                break
            if line.strip():
                payload = json.dumps({"from": device_id, "text": line.strip()})
                client.publish(chat_topic, payload, qos=1)
    except (KeyboardInterrupt, EOFError):
        pass

    print("\n[disconnecting]")
    client.publish(status_topic, json.dumps({"status": "offline"}), qos=1, retain=True)
    client.disconnect()
    client.loop_stop()


if __name__ == "__main__":
    main()
