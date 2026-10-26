package com.example.blemesh.data.maps

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * En-tête (127 octets) d'une archive PMTiles v3 : un seul fichier qui contient toutes les tuiles
 * d'une zone. On le lit pour vérifier le fichier importé, connaître son type (vectoriel ou image)
 * et centrer la carte sur la zone couverte.
 * Spécification : https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
data class PmtilesHeader(
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double
) {
    /** Tuiles vectorielles (MVT), ex. extrait Protomaps. */
    val isVector: Boolean get() = tileType == TYPE_MVT
    /** Tuiles images (PNG, JPEG, WebP, AVIF). */
    val isRaster: Boolean get() = tileType in TYPE_PNG..TYPE_AVIF

    fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        const val SIZE = 127
        const val TYPE_MVT = 1
        const val TYPE_PNG = 2
        const val TYPE_AVIF = 5
        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        /** Null si ce n'est pas une archive PMTiles v3. */
        fun parse(bytes: ByteArray): PmtilesHeader? {
            if (bytes.size < SIZE) return null
            if (!bytes.copyOfRange(0, 7).contentEquals(MAGIC) || bytes[7].toInt() != 3) return null
            val b = ByteBuffer.wrap(bytes, 0, SIZE).order(ByteOrder.LITTLE_ENDIAN)
            fun e7(offset: Int) = b.getInt(offset) / 10_000_000.0
            fun u8(offset: Int) = bytes[offset].toInt() and 0xFF
            val header = PmtilesHeader(
                tileType = u8(99),
                minZoom = u8(100),
                maxZoom = u8(101),
                minLon = e7(102),
                minLat = e7(106),
                maxLon = e7(110),
                maxLat = e7(114),
                centerZoom = u8(118),
                centerLon = e7(119),
                centerLat = e7(123)
            )
            return header.takeIf { it.isVector || it.isRaster }
        }
    }
}
