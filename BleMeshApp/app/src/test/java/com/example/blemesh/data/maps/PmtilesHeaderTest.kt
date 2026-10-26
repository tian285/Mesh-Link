package com.example.blemesh.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmtilesHeaderTest {

    /** Construit un en-tête v3 conforme à la spécification (zone d'Antananarivo). */
    private fun header(tileType: Int = 1, version: Int = 3): ByteArray {
        val b = ByteBuffer.allocate(PmtilesHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PMTiles".toByteArray()); b.put(version.toByte())
        b.position(99)
        b.put(tileType.toByte()); b.put(0); b.put(15)                 // type, minzoom, maxzoom
        b.putInt(474_000_000); b.putInt(-190_500_000)                  // min lon, min lat (×1e7)
        b.putInt(476_500_000); b.putInt(-188_000_000)                  // max lon, max lat
        b.put(12); b.putInt(475_079_000); b.putInt(-188_792_000)       // centre
        return b.array()
    }

    @Test
    fun parses_vector_archive_header() {
        val h = PmtilesHeader.parse(header())!!
        assertTrue(h.isVector)
        assertEquals(15, h.maxZoom)
        assertEquals(47.5079, h.centerLon, 1e-6)
        assertEquals(-18.8792, h.centerLat, 1e-6)
        assertTrue(h.contains(-18.91, 47.52))
        assertFalse(h.contains(-21.45, 47.08)) // Fianarantsoa, hors zone
    }

    @Test
    fun recognises_raster_and_rejects_others() {
        assertTrue(PmtilesHeader.parse(header(tileType = 2))!!.isRaster)
        assertNull(PmtilesHeader.parse(header(version = 2)))
        assertNull(PmtilesHeader.parse(header(tileType = 0)))
        assertNull(PmtilesHeader.parse(ByteArray(50)))
        assertNull(PmtilesHeader.parse("SQLite format 3\u0000".toByteArray() + ByteArray(120)))
    }

    @Test
    fun styles_are_offline_only() {
        val vector = MapStyles.vector("""{"url":"__PMTILES_URL__"}""", "/data/user/0/app/files/maps/carte.pmtiles")
        assertEquals("""{"url":"pmtiles://file:///data/user/0/app/files/maps/carte.pmtiles"}""", vector)
        listOf(MapStyles.raster("/x.pmtiles"), MapStyles.empty()).forEach {
            assertFalse(it.contains("http"))
            assertTrue(it.contains("asset://glyphs/"))
        }
    }
}
