package com.example.blemesh.domain.model

/**
 * Paquet du protocole mesh. Même structure binaire que le firmware ESP32 :
 * msg_id(4) | src_id(2) | dst_id(2) | ttl(1) | type(1) | payload(0..230)
 */
class MeshPacket(
    val msgId: Long,        // uint32
    val srcId: Int,         // uint16
    val dstId: Int,         // uint16
    val ttl: Int,           // uint8 : sauts restants autorisés
    val type: Int,          // uint8 : voir PacketType
    val payload: ByteArray  // 0..230 octets
) {
    val isBroadcast: Boolean get() = dstId == BROADCAST_ID

    /**
     * Identifiant unique du message dans tout le réseau (« hash id ») :
     * (src_id, msg_id) tassés dans un Long de 48 bits utiles.
     * Deux émetteurs différents peuvent tirer le même msg_id aléatoire, d'où l'ajout de src_id.
     */
    val key: Long get() = messageKey(srcId, msgId)

    /** Copie identique avec un autre TTL (utilisé au relais, après décrémentation). */
    fun withTtl(newTtl: Int): MeshPacket = MeshPacket(msgId, srcId, dstId, newTtl, type, payload)

    companion object {
        const val BROADCAST_ID = 0xFFFF
        const val DEFAULT_TTL = 5
        const val HEADER_SIZE = 10
        const val MAX_PAYLOAD = 230

        fun messageKey(srcId: Int, msgId: Long): Long =
            ((srcId.toLong() and 0xFFFFL) shl 32) or (msgId and 0xFFFFFFFFL)
    }
}

object PacketType {
    const val TEXT = 0
    const val GPS = 1
    const val HELLO = 2   // voisin direct, jamais relayé (TTL 1); payload = pseudo UTF-8 (facultatif)
    const val ACK = 3
    const val NAME = 4    // annonce de pseudo en diffusion, relayée comme un message normal
}
