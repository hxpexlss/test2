#!/usr/bin/env python3
"""Zwift Click -> MyWhoosh bridge for macOS.

Connects to a Zwift Click (v1) or Zwift Click v2 over Bluetooth LE and turns
button presses into key presses that MyWhoosh understands
(I = shift up, K = shift down).

Usage:
    python3 click_bridge.py            # right puck only (recommended for Click v2)
    python3 click_bridge.py --both     # also use the left puck of a Click v2
    python3 click_bridge.py --scan     # just list Zwift devices nearby
    python3 click_bridge.py --debug    # print every raw message
"""

from __future__ import annotations

import argparse
import asyncio
import shutil
import subprocess
import sys
import time

# --- Zwift BLE protocol -------------------------------------------------------

ZWIFT_MANUFACTURER_ID = 0x094A

SERVICE_UUIDS = (
    "0000fc82-0000-1000-8000-00805f9b34fb",  # newer firmware / Click v2
    "00000001-19ca-4651-86e5-fa29dcdd09d1",  # older firmware / Click v1
)
ASYNC_CHAR = "00000002-19ca-4651-86e5-fa29dcdd09d1"  # notify: button events
SYNC_RX_CHAR = "00000003-19ca-4651-86e5-fa29dcdd09d1"  # write: commands
SYNC_TX_CHAR = "00000004-19ca-4651-86e5-fa29dcdd09d1"  # indicate: responses

RIDE_ON = b"RideOn"

# First byte of the manufacturer data tells which device it is.
DEVICE_CLICK_V1 = 0x09
DEVICE_CLICK_V2_RIGHT = 0x0A
DEVICE_CLICK_V2_LEFT = 0x0B

DEVICE_NAMES = {
    DEVICE_CLICK_V1: "Zwift Click",
    DEVICE_CLICK_V2_RIGHT: "Zwift Click v2 (rechts)",
    DEVICE_CLICK_V2_LEFT: "Zwift Click v2 (links)",
}

# Message types (first byte of a notification).
MSG_CLICK_V1_BUTTONS = 0x37
MSG_RIDE_BUTTONS = 0x23  # Click v2 / Ride / Play fw2

# Bits in the Click v2 button map. A CLEARED bit means "pressed".
BTN_LEFT = 0x00001
BTN_UP = 0x00002
BTN_RIGHT = 0x00004
BTN_DOWN = 0x00008
BTN_A = 0x00010
BTN_B = 0x00020
BTN_Y = 0x00040
BTN_Z = 0x00080
BTN_SHIFT_LEFT = 0x00100  # the "-" button on the left puck
BTN_SHIFT_RIGHT = 0x01000  # the "+" button on the right puck

BUTTON_NAMES = {
    BTN_LEFT: "links",
    BTN_UP: "hoch",
    BTN_RIGHT: "rechts",
    BTN_DOWN: "runter",
    BTN_A: "A",
    BTN_B: "B",
    BTN_Y: "Y",
    BTN_Z: "Z",
    BTN_SHIFT_LEFT: "-",
    BTN_SHIFT_RIGHT: "+",
}

SHIFT_UP = "up"
SHIFT_DOWN = "down"

# Which button does what. B on the right puck shifts down so the right puck
# alone can do both directions (the left puck gets locked by Zwift).
BUTTON_ACTIONS = {
    BTN_SHIFT_RIGHT: SHIFT_UP,
    BTN_B: SHIFT_DOWN,
    BTN_SHIFT_LEFT: SHIFT_DOWN,
}


def read_varint(data: bytes, pos: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if pos >= len(data):
            raise ValueError("truncated varint")
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def parse_varint_fields(data: bytes) -> dict[int, int]:
    """Minimal protobuf reader: returns the varint fields, skips the rest."""
    fields: dict[int, int] = {}
    pos = 0
    while pos < len(data):
        key, pos = read_varint(data, pos)
        field, wire_type = key >> 3, key & 0x07
        if wire_type == 0:
            fields[field], pos = read_varint(data, pos)
        elif wire_type == 1:
            pos += 8
        elif wire_type == 2:
            length, pos = read_varint(data, pos)
            pos += length
        elif wire_type == 5:
            pos += 4
        else:
            raise ValueError(f"unsupported wire type {wire_type}")
    return fields


def pressed_buttons(message: bytes) -> set[int] | None:
    """Return the set of pressed buttons for a notification, or None if the
    message is not a button message."""
    if not message:
        return None
    msg_type, payload = message[0], message[1:]

    if msg_type == MSG_RIDE_BUTTONS:
        fields = parse_varint_fields(payload)
        if 1 not in fields:
            return None
        button_map = fields[1]
        return {bit for bit in BUTTON_NAMES if not button_map & bit}

    if msg_type == MSG_CLICK_V1_BUTTONS:
        # Field 1 = plus, field 2 = minus; 0 (also: field missing) = pressed.
        fields = parse_varint_fields(payload)
        pressed = set()
        if fields.get(1, 0) == 0:
            pressed.add(BTN_SHIFT_RIGHT)
        if fields.get(2, 0) == 0:
            pressed.add(BTN_SHIFT_LEFT)
        return pressed

    return None


# --- Key presses on macOS -----------------------------------------------------

# macOS virtual key codes (physical key positions, same on QWERTZ and QWERTY).
MAC_KEY_CODES = {
    "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8,
    "v": 9, "b": 11, "q": 12, "w": 13, "e": 14, "r": 15, "y": 16, "t": 17,
    "o": 31, "u": 32, "i": 34, "p": 35, "l": 37, "j": 38, "k": 40, "n": 45,
    "m": 46, "up": 126, "down": 125, "left": 123, "right": 124, "space": 49,
}


class KeySender:
    def __init__(self) -> None:
        self._quartz = None
        try:
            import Quartz  # type: ignore

            self._quartz = Quartz
        except ImportError:
            if sys.platform == "darwin":
                print("Hinweis: pyobjc-framework-Quartz fehlt, nutze osascript (etwas langsamer).")

    def press(self, key: str) -> None:
        code = MAC_KEY_CODES[key]
        if self._quartz is not None:
            q = self._quartz
            for down in (True, False):
                event = q.CGEventCreateKeyboardEvent(None, code, down)
                q.CGEventPost(q.kCGHIDEventTap, event)
                if down:
                    time.sleep(0.03)  # games poll the keyboard, keep the key down briefly
        elif sys.platform == "darwin" and shutil.which("osascript"):
            subprocess.run(
                ["osascript", "-e", f'tell application "System Events" to key code {code}'],
                check=False,
            )
        else:
            print(f"(Testmodus: würde Taste '{key}' drücken)")


# --- Bluetooth ----------------------------------------------------------------


def zwift_device_type(adv) -> int | None:
    data = adv.manufacturer_data.get(ZWIFT_MANUFACTURER_ID)
    if not data:
        return None
    return data[0]


_scan_lock = asyncio.Lock()


async def scan(timeout: float) -> dict[int, object]:
    """Return {device_type: BLEDevice} for Zwift Clicks in range."""
    from bleak import BleakScanner

    found: dict[int, object] = {}
    async with _scan_lock:  # one scan at a time when both pucks reconnect
        results = await BleakScanner.discover(timeout=timeout, return_adv=True)
    for device, adv in results.values():
        device_type = zwift_device_type(adv)
        if device_type in DEVICE_NAMES:
            found[device_type] = device
    return found


class ClickConnection:
    def __init__(self, device_type: int, keys: KeySender, up_key: str, down_key: str, debug: bool):
        self.device_type = device_type
        self.name = DEVICE_NAMES[device_type]
        self.keys = keys
        self.key_for_action = {SHIFT_UP: up_key, SHIFT_DOWN: down_key}
        self.debug = debug
        self._last_pressed: set[int] = set()

    def on_message(self, _sender, data: bytearray) -> None:
        data = bytes(data)
        if self.debug:
            print(f"[{self.name}] {data.hex(' ')}")
        try:
            pressed = pressed_buttons(data)
        except ValueError as error:
            print(f"[{self.name}] Nachricht nicht lesbar ({error}): {data.hex(' ')}")
            return
        if pressed is None:
            return

        # The device repeats its state; only react when a button goes down.
        newly_pressed = pressed - self._last_pressed
        self._last_pressed = pressed
        for button in newly_pressed:
            action = BUTTON_ACTIONS.get(button)
            if self.debug or action:
                print(f"[{self.name}] Taste {BUTTON_NAMES[button]}" + (f" -> {action}" if action else ""))
            if action:
                self.keys.press(self.key_for_action[action])

    async def run_forever(self, scan_timeout: float) -> None:
        from bleak import BleakClient

        while True:
            device = (await scan(scan_timeout)).get(self.device_type)
            if device is None:
                print(f"{self.name} nicht gefunden – drück eine Taste am Click, ich suche weiter …")
                continue

            disconnected = asyncio.Event()
            try:
                async with BleakClient(device, disconnected_callback=lambda _c: disconnected.set()) as client:
                    if not any(client.services.get_service(uuid) for uuid in SERVICE_UUIDS):
                        print(
                            f"{self.name}: Zwift-Dienst nicht gefunden. "
                            "Evtl. Firmware im Zwift-Companion-App aktualisieren."
                        )
                        await asyncio.sleep(5)
                        continue
                    await client.start_notify(ASYNC_CHAR, self.on_message)
                    await client.start_notify(SYNC_TX_CHAR, self.on_message)
                    handshake = RIDE_ON
                    if self.device_type != DEVICE_CLICK_V1:
                        handshake += bytes([0x02, 0x03])
                    await client.write_gatt_char(SYNC_RX_CHAR, handshake, response=False)
                    self._last_pressed = set()
                    print(f"✅ {self.name} verbunden.")
                    await disconnected.wait()
            except Exception as error:  # bleak raises various platform errors
                print(f"{self.name}: Verbindungsfehler: {error}")
            print(f"{self.name} getrennt – verbinde neu …")
            await asyncio.sleep(1)


async def main_async(args: argparse.Namespace) -> None:
    if args.scan:
        print("Suche Zwift-Geräte (10 s) – drück eine Taste am Click, damit er aufwacht …")
        found = await scan(10)
        if not found:
            print("Nichts gefunden.")
        for device_type, device in found.items():
            print(f"  {DEVICE_NAMES[device_type]}: {device.address}")
        return

    keys = KeySender()
    print("Suche Zwift Click … (drück eine Taste am Click, damit er aufwacht)")
    found: dict[int, object] = {}
    while not found:
        found = await scan(args.scan_timeout)

    wanted = [DEVICE_CLICK_V1, DEVICE_CLICK_V2_RIGHT]
    if args.both:
        wanted.append(DEVICE_CLICK_V2_LEFT)
    elif DEVICE_CLICK_V2_LEFT in found:
        print("Hinweis: Linke Seite des Click v2 wird ignoriert (Zwift sperrt sie). Mit --both trotzdem nutzen.")

    targets = [t for t in wanted if t in found]
    if not targets:
        print("Gefunden wurde nur die linke Seite eines Click v2. Schalte die rechte Seite ein oder nutze --both.")
        return

    print(f"Schalten: hoch = '{args.up_key.upper()}', runter = '{args.down_key.upper()}'.")
    print("Lass MyWhoosh im Vordergrund. Beenden mit Ctrl+C.\n")
    connections = [ClickConnection(t, keys, args.up_key, args.down_key, args.debug) for t in targets]
    await asyncio.gather(*(c.run_forever(args.scan_timeout) for c in connections))


def main() -> None:
    parser = argparse.ArgumentParser(description="Zwift Click -> MyWhoosh (Tastatur) Brücke")
    parser.add_argument("--both", action="store_true", help="auch die linke Seite des Click v2 verbinden")
    parser.add_argument("--scan", action="store_true", help="nur nach Zwift-Geräten suchen und anzeigen")
    parser.add_argument("--debug", action="store_true", help="alle Bluetooth-Nachrichten anzeigen")
    parser.add_argument("--up-key", default="i", choices=sorted(MAC_KEY_CODES), help="Taste für hochschalten")
    parser.add_argument("--down-key", default="k", choices=sorted(MAC_KEY_CODES), help="Taste für runterschalten")
    parser.add_argument("--scan-timeout", type=float, default=5.0, help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        print("\nTschüss!")


if __name__ == "__main__":
    main()
