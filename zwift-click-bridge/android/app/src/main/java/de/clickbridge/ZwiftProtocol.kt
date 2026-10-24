package de.clickbridge

import java.util.UUID

/** Zwift Click BLE protocol: constants and button message parsing. */
object ZwiftProtocol {
    const val MANUFACTURER_ID = 0x094A

    val SERVICE_UUIDS: List<UUID> = listOf(
        UUID.fromString("0000fc82-0000-1000-8000-00805f9b34fb"), // newer firmware / Click v2
        UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1"), // older firmware / Click v1
    )
    val ASYNC_CHAR: UUID = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1") // notify: buttons
    val SYNC_RX_CHAR: UUID = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1") // write: commands
    val SYNC_TX_CHAR: UUID = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1") // indicate: responses
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // First byte of the manufacturer data.
    const val DEVICE_CLICK_V1 = 0x09
    const val DEVICE_CLICK_V2_RIGHT = 0x0A
    const val DEVICE_CLICK_V2_LEFT = 0x0B

    private const val MSG_CLICK_V1_BUTTONS = 0x37
    private const val MSG_RIDE_BUTTONS = 0x23

    // Bits in the Click v2 button map. A CLEARED bit means "pressed".
    const val BTN_B = 0x00020
    const val BTN_MINUS = 0x00100 // left puck "-" / Click v1 "-"
    const val BTN_PLUS = 0x01000 // right puck "+" / Click v1 "+"
    private val ALL_BUTTONS = listOf(
        0x00001, 0x00002, 0x00004, 0x00008, 0x00010, BTN_B, 0x00040, 0x00080, BTN_MINUS, BTN_PLUS,
    )

    fun deviceName(type: Int): String? = when (type) {
        DEVICE_CLICK_V1 -> "Zwift Click"
        DEVICE_CLICK_V2_RIGHT -> "Zwift Click v2 (rechts)"
        DEVICE_CLICK_V2_LEFT -> "Zwift Click v2 (links)"
        else -> null
    }

    fun handshake(type: Int): ByteArray {
        val rideOn = "RideOn".toByteArray()
        return if (type == DEVICE_CLICK_V1) rideOn else rideOn + byteArrayOf(0x02, 0x03)
    }

    enum class Shift { UP, DOWN }

    fun actionFor(button: Int): Shift? = when (button) {
        BTN_PLUS -> Shift.UP
        BTN_MINUS, BTN_B -> Shift.DOWN
        else -> null
    }

    /** Pressed buttons in a notification, or null if it is not a button message. */
    fun pressedButtons(message: ByteArray): Set<Int>? {
        if (message.isEmpty()) return null
        val type = message[0].toInt() and 0xFF
        val payload = message.copyOfRange(1, message.size)
        return when (type) {
            MSG_RIDE_BUTTONS -> {
                val buttonMap = varintFields(payload)[1] ?: return null
                ALL_BUTTONS.filter { buttonMap and it.toLong() == 0L }.toSet()
            }
            MSG_CLICK_V1_BUTTONS -> {
                // Field 1 = plus, field 2 = minus; 0 (also: field missing) = pressed.
                val fields = varintFields(payload)
                buildSet {
                    if ((fields[1] ?: 0L) == 0L) add(BTN_PLUS)
                    if ((fields[2] ?: 0L) == 0L) add(BTN_MINUS)
                }
            }
            else -> null
        }
    }

    /** Minimal protobuf reader: returns the varint fields, skips the rest. */
    fun varintFields(data: ByteArray): Map<Int, Long> {
        val fields = mutableMapOf<Int, Long>()
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < data.size) { "truncated varint" }
                val byte = data[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < data.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when ((key and 0x07).toInt()) {
                0 -> fields[field] = readVarint()
                1 -> pos += 8
                2 -> {
                    // Read the length first: `pos += readVarint()` would use the old pos.
                    val length = readVarint().toInt()
                    pos += length
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type")
            }
        }
        return fields
    }
}
