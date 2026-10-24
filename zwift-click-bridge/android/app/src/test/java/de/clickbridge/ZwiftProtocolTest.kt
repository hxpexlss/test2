package de.clickbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZwiftProtocolTest {
    private fun varint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out += (byte or 0x80).toByte() else {
                out += byte.toByte()
                return out.toByteArray()
            }
        }
    }

    private fun rideMessage(buttonMap: Long) = byteArrayOf(0x23, 0x08) + varint(buttonMap)

    private val allReleased = 0xFFFFFFFFL

    @Test
    fun clickV2NothingPressed() {
        assertEquals(emptySet<Int>(), ZwiftProtocol.pressedButtons(rideMessage(allReleased)))
    }

    @Test
    fun clickV2PlusPressedWithAnalogField() {
        val analog = byteArrayOf(0x1A, 0x04, 0x08, 0x00, 0x10, 0x00)
        val message = rideMessage(allReleased and ZwiftProtocol.BTN_PLUS.toLong().inv()) + analog
        assertEquals(setOf(ZwiftProtocol.BTN_PLUS), ZwiftProtocol.pressedButtons(message))
    }

    @Test
    fun clickV1() {
        assertEquals(
            setOf(ZwiftProtocol.BTN_PLUS),
            ZwiftProtocol.pressedButtons(byteArrayOf(0x37, 0x08, 0x00, 0x10, 0x01)),
        )
        assertEquals(emptySet<Int>(), ZwiftProtocol.pressedButtons(byteArrayOf(0x37, 0x08, 0x01, 0x10, 0x01)))
        assertEquals(setOf(ZwiftProtocol.BTN_MINUS), ZwiftProtocol.pressedButtons(byteArrayOf(0x37, 0x08, 0x01)))
    }

    @Test
    fun otherMessagesIgnored() {
        assertNull(ZwiftProtocol.pressedButtons(byteArrayOf(0x15)))
        assertNull(ZwiftProtocol.pressedButtons(byteArrayOf(0x19, 0x08, 0x50)))
        assertNull(ZwiftProtocol.pressedButtons(byteArrayOf()))
    }

    @Test
    fun handshake() {
        assertEquals("RideOn", String(ZwiftProtocol.handshake(ZwiftProtocol.DEVICE_CLICK_V1)))
        assertEquals(8, ZwiftProtocol.handshake(ZwiftProtocol.DEVICE_CLICK_V2_RIGHT).size)
    }
}
