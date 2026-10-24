import unittest

from click_bridge import (
    BTN_B,
    BTN_SHIFT_LEFT,
    BTN_SHIFT_RIGHT,
    ClickConnection,
    DEVICE_CLICK_V2_RIGHT,
    pressed_buttons,
)


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def ride_message(button_map: int) -> bytes:
    return bytes([0x23, 0x08]) + varint(button_map)


ALL_RELEASED = 0xFFFFFFFF


class ParserTest(unittest.TestCase):
    def test_click_v2_nothing_pressed(self):
        self.assertEqual(pressed_buttons(ride_message(ALL_RELEASED)), set())

    def test_click_v2_plus_pressed(self):
        self.assertEqual(pressed_buttons(ride_message(ALL_RELEASED & ~BTN_SHIFT_RIGHT)), {BTN_SHIFT_RIGHT})

    def test_click_v2_with_analog_field(self):
        analog = bytes([0x1A, 0x04, 0x08, 0x00, 0x10, 0x00])  # field 3, length-delimited
        message = ride_message(ALL_RELEASED & ~BTN_B) + analog
        self.assertEqual(pressed_buttons(message), {BTN_B})

    def test_click_v1(self):
        self.assertEqual(pressed_buttons(bytes([0x37, 0x08, 0x00, 0x10, 0x01])), {BTN_SHIFT_RIGHT})
        self.assertEqual(pressed_buttons(bytes([0x37, 0x08, 0x01, 0x10, 0x01])), set())
        self.assertEqual(pressed_buttons(bytes([0x37, 0x08, 0x01])), {BTN_SHIFT_LEFT})  # missing = pressed

    def test_other_messages_ignored(self):
        self.assertIsNone(pressed_buttons(bytes([0x15])))
        self.assertIsNone(pressed_buttons(bytes([0x19, 0x08, 0x50])))
        self.assertIsNone(pressed_buttons(b""))


class FakeKeys:
    def __init__(self):
        self.pressed = []

    def press(self, key):
        self.pressed.append(key)


class ConnectionTest(unittest.TestCase):
    def test_press_once_per_button_down(self):
        keys = FakeKeys()
        conn = ClickConnection(DEVICE_CLICK_V2_RIGHT, keys, "i", "k", debug=False)
        plus = ride_message(ALL_RELEASED & ~BTN_SHIFT_RIGHT)
        b = ride_message(ALL_RELEASED & ~BTN_B)
        for msg in (plus, plus, ride_message(ALL_RELEASED), plus, b, ride_message(ALL_RELEASED)):
            conn.on_message(None, bytearray(msg))
        self.assertEqual(keys.pressed, ["i", "i", "k"])


if __name__ == "__main__":
    unittest.main()
