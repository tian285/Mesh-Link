package com.example.blemesh.data.protocol

import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.Pseudo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sérialisation binaire compatible avec la structure C++ de l'ESP32
 * (#pragma pack(1), little-endian : octet de poids faible en premier).
 */
object PacketCodec {

    fun encode(packet: MeshPacket): ByteArray {
        val buf = ByteBuffer.allocate(MeshPacket.HEADER_SIZE + packet.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(packet.msgId.toInt())
        buf.putShort(packet.srcId.toShort())
        buf.putShort(packet.dstId.toShort())
        buf.put(packet.ttl.toByte())
        buf.put(packet.type.toByte())
        buf.put(packet.payload)
        return buf.array()
    }

    /** Retourne null si la trame est trop courte ou trop longue. */
    fun decode(bytes: ByteArray): MeshPacket? {
        if (bytes.size < MeshPacket.HEADER_SIZE) return null
        if (bytes.size > MeshPacket.HEADER_SIZE + MeshPacket.MAX_PAYLOAD) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val msgId = buf.int.toLong() and 0xFFFFFFFFL
        val srcId = buf.short.toInt() and 0xFFFF
        val dstId = buf.short.toInt() and 0xFFFF
        val ttl = buf.get().toInt() and 0xFF
        val type = buf.get().toInt() and 0xFF
        val payload = ByteArray(bytes.size - MeshPacket.HEADER_SIZE)
        buf.get(payload)
        return MeshPacket(msgId, srcId, dstId, ttl, type, payload)
    }

    // --- Payloads -------------------------------------------------------------

    fun textPayload(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun parseText(payload: ByteArray): String =
        String(payload, Charsets.UTF_8).trimEnd('\u0000')

    /**
     * GPS : latitude (float 4 octets) puis longitude (float 4 octets), puis, en option,
     * le pseudo de l'émetteur en UTF-8. L'ESP32 ne lit que les 8 premiers octets : compatible.
     */
    fun gpsPayload(latitude: Float, longitude: Float, pseudo: String? = null): ByteArray {
        val name = pseudoBytes(pseudo)
        return ByteBuffer.allocate(8 + name.size).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(latitude).putFloat(longitude).put(name).array()
    }

    fun parseGps(payload: ByteArray): Pair<Float, Float>? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return Pair(buf.float, buf.float)
    }

    /** Pseudo éventuellement ajouté après les coordonnées GPS. */
    fun parseGpsPseudo(payload: ByteArray): String? =
        if (payload.size <= 8) null else parsePseudo(payload.copyOfRange(8, payload.size))

    // --- Pseudo (HELLO, NAME, suffixe GPS) -------------------------------------

    /** Pseudo nettoyé et tronqué, en UTF-8. Tableau vide si pas de pseudo. */
    fun pseudoBytes(pseudo: String?): ByteArray =
        Pseudo.sanitize(pseudo)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

    /** Décode un pseudo reçu (nettoyé). Null si vide ou invalide. */
    fun parsePseudo(payload: ByteArray): String? =
        if (payload.isEmpty()) null
        else Pseudo.sanitize(String(payload, Charsets.UTF_8).trimEnd('\u0000'))

    /** ACK : l'ID (4 octets) du message d'origine auquel on répond. */
    fun ackPayload(originalMsgId: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(originalMsgId.toInt()).array()

    fun parseAck(payload: ByteArray): Long? {
        if (payload.size < 4) return null
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }
}
