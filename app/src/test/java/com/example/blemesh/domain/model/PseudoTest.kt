package com.example.blemesh.domain.model

import com.example.blemesh.data.protocol.PacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PseudoTest {

    @Test
    fun sanitize_trims_controls_and_spaces() {
        assertEquals("Haja Rakoto", Pseudo.sanitize("  Haja\n  Rakoto\u0000 "))
        assertNull(Pseudo.sanitize("   "))
        assertNull(Pseudo.sanitize(null))
    }

    @Test
    fun truncate_never_cuts_a_character_in_half() {
        val s = "éééééééééééééééé" // 16 × 2 octets
        val t = Pseudo.truncateUtf8(s, 5)
        assertEquals("éé", t)
        assertEquals(24, Pseudo.sanitize("é".repeat(30))!!.toByteArray().size)
    }

    @Test
    fun gps_payload_with_pseudo_keeps_esp32_layout() {
        val payload = PacketCodec.gpsPayload(-18.91f, 47.52f, "Haja")
        val (lat, lon) = PacketCodec.parseGps(payload)!!
        assertEquals(-18.91f, lat, 0.0001f)
        assertEquals(47.52f, lon, 0.0001f)
        assertEquals("Haja", PacketCodec.parseGpsPseudo(payload))
        assertNull(PacketCodec.parseGpsPseudo(PacketCodec.gpsPayload(1f, 2f)))
        assertEquals(8, PacketCodec.gpsPayload(1f, 2f, null).size)
    }

    @Test
    fun display_names_disambiguate_homonyms_and_prefer_alias() {
        val contacts = mapOf(
            0x1001 to Contact(0x1001, announcedName = "Haja"),
            0x1002 to Contact(0x1002, announcedName = "Haja"),
            0x1003 to Contact(0x1003, announcedName = "Tiana", alias = "Ma sœur")
        )
        val names = DisplayNames(contacts, myNodeId = 0x1009, myPseudo = "Moi")
        assertEquals("Haja #1001", names.of(0x1001))
        assertEquals("Ma sœur", names.of(0x1003))
        assertEquals("Nœud 0042", names.of(0x0042))
        assertEquals("Tout le monde", names.of(MeshPacket.BROADCAST_ID))
        assertEquals(0x1003, names.resolve("tiana"))
        assertNull("ambigu", names.resolve("Haja"))
    }
}
