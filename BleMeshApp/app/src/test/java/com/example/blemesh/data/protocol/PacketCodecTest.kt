package com.example.blemesh.data.protocol

import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketCodecTest {

    @Test
    fun encode_matches_esp32_binary_layout() {
        val packet = MeshPacket(0x11223344L, 0x0001, 0xFFFF, 5, PacketType.TEXT, "Hi".toByteArray())
        val expected = byteArrayOf(
            0x44, 0x33, 0x22, 0x11,          // msg_id little-endian
            0x01, 0x00,                      // src_id
            0xFF.toByte(), 0xFF.toByte(),    // dst_id (broadcast)
            0x05,                            // ttl
            0x00,                            // type TEXT
            0x48, 0x69                       // "Hi"
        )
        assertArrayEquals(expected, PacketCodec.encode(packet))
    }

    @Test
    fun decode_roundtrip_keeps_unsigned_values() {
        val packet = MeshPacket(0xFFFFFFFEL, 0xABCD, 0x1234, 200, PacketType.ACK, byteArrayOf(1, 2, 3, 4))
        val decoded = PacketCodec.decode(PacketCodec.encode(packet))!!
        assertEquals(0xFFFFFFFEL, decoded.msgId)
        assertEquals(0xABCD, decoded.srcId)
        assertEquals(0x1234, decoded.dstId)
        assertEquals(200, decoded.ttl)
        assertEquals(PacketType.ACK, decoded.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.payload)
    }

    @Test
    fun decode_rejects_invalid_sizes() {
        assertNull(PacketCodec.decode(ByteArray(9)))
        assertNull(PacketCodec.decode(ByteArray(MeshPacket.HEADER_SIZE + MeshPacket.MAX_PAYLOAD + 1)))
    }

    @Test
    fun ack_payload_roundtrip() {
        assertEquals(0xDEADBEEFL, PacketCodec.parseAck(PacketCodec.ackPayload(0xDEADBEEFL)))
        assertNull(PacketCodec.parseAck(ByteArray(3)))
    }

    @Test
    fun gps_payload_roundtrip() {
        val (lat, lon) = PacketCodec.parseGps(PacketCodec.gpsPayload(-18.91f, 47.52f))!!
        assertEquals(-18.91f, lat, 0.0001f)
        assertEquals(47.52f, lon, 0.0001f)
        assertNull(PacketCodec.parseGps(ByteArray(7)))
    }

    @Test
    fun text_payload_ignores_trailing_zero_padding() {
        val raw = "Salut".toByteArray() + ByteArray(3)
        assertEquals("Salut", PacketCodec.parseText(raw))
    }
}
