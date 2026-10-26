package com.example.blemesh.data.mesh

import com.example.blemesh.data.protocol.PacketCodec
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.MeshRoute
import com.example.blemesh.domain.model.PacketType

/**
 * Cœur du routage côté téléphone, sans aucune dépendance Android (testable sur la JVM).
 *
 * ÉMISSION d'un message unicast (ordre de priorité demandé) :
 *   1. le destinataire est un voisin BLE relié          → envoi DIRECT à lui seul;
 *   2. sinon une passerelle ESP32 + LoRa est connectée   → envoi à la PASSERELLE;
 *   3. sinon                                             → INONDATION vers tous les téléphones reliés,
 *      qui relaient à leur tour vers d'autres téléphones ou vers leur propre ESP32.
 * Les diffusions (dst = FFFF : HELLO, NAME, GPS, texte à tous) partent sur tous les liens.
 *
 * RÉCEPTION / RELAIS par un téléphone qui n'est pas le destinataire :
 *   - écho de mes propres paquets               → ignoré;
 *   - clé (src_id, msg_id) déjà vue              → ignoré et NON relayé (verrou anti-boucle n° 1);
 *   - TTL ≤ 1 ou HELLO                           → pas de relais (verrou n° 2 : nombre de sauts borné);
 *   - sinon TTL − 1, puis : lien direct vers le destinataire s'il existe, sinon passerelle + téléphones,
 *     JAMAIS vers le lien d'arrivée (verrou n° 3 : « split horizon ») ni vers la passerelle si le paquet
 *     en vient (l'ESP32 a déjà fait le relais LoRa).
 */
class MeshRouter(
    private val transport: MeshTransport,
    private val myId: () -> Int,
    val seen: SeenCache = SeenCache()
) {
    /** Paquet nouveau reçu, à livrer localement si dst = moi ou diffusion. */
    class Received(val packet: MeshPacket, val from: Link, val relayRoute: MeshRoute)

    /**
     * Émet un paquet créé par ce téléphone.
     * @param everywhere true = passerelle + tous les téléphones même pour un unicast
     *                   (utilisé pour les nouvelles tentatives quand l'ACK n'arrive pas).
     */
    fun originate(packet: MeshPacket, everywhere: Boolean = false): MeshRoute {
        seen.add(packet.key) // pour ignorer le paquet s'il nous revient par un autre chemin
        val frame = PacketCodec.encode(packet)
        if (!packet.isBroadcast && !everywhere) {
            transport.phoneLinkTo(packet.dstId)?.let { address ->
                if (transport.sendToPhone(address, frame)) return MeshRoute.DIRECT
            }
            if (transport.sendToGateway(frame)) return MeshRoute.GATEWAY
            return if (transport.floodPhones(frame, null) > 0) MeshRoute.FLOOD else MeshRoute.NONE
        }
        return fanOut(frame, fromGateway = false, exceptAddress = null)
    }

    /**
     * Traite une trame reçue sur [from]. Relaie si nécessaire.
     * @return le paquet s'il est nouveau, null s'il est invalide, en double ou en écho.
     */
    fun receive(frame: ByteArray, from: Link): Received? {
        val packet = PacketCodec.decode(frame) ?: return null
        val me = myId()
        if (packet.srcId == me) return null
        if (!seen.add(packet.key)) return null
        val route = if (shouldRelay(packet, me)) relay(packet, from) else MeshRoute.NONE
        return Received(packet, from, route)
    }

    private fun shouldRelay(p: MeshPacket, me: Int): Boolean =
        p.dstId != me && p.type != PacketType.HELLO && p.ttl > 1

    private fun relay(p: MeshPacket, from: Link): MeshRoute {
        val out = PacketCodec.encode(p.withTtl(p.ttl - 1))
        val fromAddress = (from as? Link.Phone)?.address
        if (!p.isBroadcast) {
            val direct = transport.phoneLinkTo(p.dstId)
            if (direct != null && direct != fromAddress && transport.sendToPhone(direct, out)) {
                return MeshRoute.DIRECT
            }
        }
        return fanOut(out, fromGateway = from is Link.Gateway, exceptAddress = fromAddress)
    }

    private fun fanOut(frame: ByteArray, fromGateway: Boolean, exceptAddress: String?): MeshRoute {
        val gateway = !fromGateway && transport.sendToGateway(frame)
        val phones = transport.floodPhones(frame, exceptAddress)
        return when {
            gateway && phones > 0 -> MeshRoute.GATEWAY_AND_FLOOD
            gateway -> MeshRoute.GATEWAY
            phones > 0 -> MeshRoute.FLOOD
            else -> MeshRoute.NONE
        }
    }
}
