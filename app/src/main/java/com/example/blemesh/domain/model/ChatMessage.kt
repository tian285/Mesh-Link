package com.example.blemesh.domain.model

data class ChatMessage(
    val msgId: Long,
    val isFromMe: Boolean,
    val srcId: Int,
    val dstId: Int,
    val text: String,
    val timestampMs: Long,
    val status: DeliveryStatus,
    /** Chemin utilisé au départ (messages envoyés uniquement). */
    val route: MeshRoute? = null,
    /** Nombre de sauts parcourus (messages reçus) = TTL initial − TTL à l'arrivée. */
    val hops: Int? = null
) {
    val isBroadcast: Boolean get() = dstId == MeshPacket.BROADCAST_ID
}
