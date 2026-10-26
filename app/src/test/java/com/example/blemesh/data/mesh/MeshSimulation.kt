package com.example.blemesh.data.mesh

import com.example.blemesh.data.protocol.PacketCodec
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.MeshRoute
import com.example.blemesh.domain.model.PacketType
import kotlin.random.Random

/**
 * Petit simulateur de réseau pour les tests : des téléphones (vrai [MeshRouter]) reliés en BLE,
 * et des ESP32 qui reproduisent fidèlement handlePacket() de LoRaMesh.ino.
 * Les trames passent par une file d'attente (largeur d'abord) : pas de récursion, ordre réaliste.
 */
class MeshSimulation {

    private sealed interface Hop
    private data class ToPhone(val phoneId: Int, val frame: ByteArray, val from: Link) : Hop
    private data class ToEsp(val espId: Int, val frame: ByteArray, val fromBle: Boolean) : Hop

    private val queue = ArrayDeque<Hop>()
    val phones = linkedMapOf<Int, Phone>()
    val esps = linkedMapOf<Int, Esp>()

    /** Nombre total de trames émises sur l'air (BLE + LoRa). */
    var transmissions = 0
        private set

    // --- Construction de la topologie ------------------------------------------
    fun phone(id: Int): Phone = phones.getOrPut(id) { Phone(id) }
    fun esp(id: Int): Esp = esps.getOrPut(id) { Esp(id) }

    fun bleLink(a: Int, b: Int) {
        phone(a).neighbors += b
        phone(b).neighbors += a
    }

    fun attachGateway(phoneId: Int, espId: Int) {
        phone(phoneId).gateway = espId
        esp(espId).phones += phoneId
    }

    fun loraLink(a: Int, b: Int) {
        esp(a).loraPeers += b
        esp(b).loraPeers += a
    }

    fun addressOf(phoneId: Int) = "phone-%04X".format(phoneId)

    /** Traite toutes les trames en attente. Échoue si le réseau ne se calme pas (boucle infinie). */
    fun run(maxSteps: Int = 100_000) {
        var steps = 0
        while (queue.isNotEmpty()) {
            check(++steps <= maxSteps) { "Le réseau ne converge pas : boucle probable" }
            when (val hop = queue.removeFirst()) {
                is ToPhone -> phones.getValue(hop.phoneId).onFrame(hop.frame, hop.from)
                is ToEsp -> esps.getValue(hop.espId).onFrame(hop.frame, hop.fromBle)
            }
        }
    }

    // --- Téléphone -----------------------------------------------------------------
    inner class Phone(val id: Int) {
        val neighbors = linkedSetOf<Int>()
        var gateway: Int? = null
        /** Paquets livrés à l'application (clé → nombre de livraisons). */
        val delivered = linkedMapOf<Long, Int>()
        val acked = mutableSetOf<Long>()
        /** Trames émises par ce téléphone : (lien de sortie, trame). */
        val sent = mutableListOf<Pair<String, ByteArray>>()

        private val transport = object : MeshTransport {
            override fun phoneLinkTo(nodeId: Int): String? =
                if (nodeId in neighbors) addressOf(nodeId) else null

            override fun sendToGateway(frame: ByteArray): Boolean {
                val esp = gateway ?: return false
                emit("gateway", frame)
                queue += ToEsp(esp, frame, fromBle = true)
                return true
            }

            override fun sendToPhone(address: String, frame: ByteArray): Boolean {
                val target = neighbors.firstOrNull { addressOf(it) == address } ?: return false
                emit(address, frame)
                queue += ToPhone(target, frame, Link.Phone(addressOf(id)))
                return true
            }

            override fun floodPhones(frame: ByteArray, exceptAddress: String?): Int =
                neighbors.count { n -> addressOf(n) != exceptAddress && sendToPhone(addressOf(n), frame) }
        }

        val router = MeshRouter(transport, { id })

        private fun emit(link: String, frame: ByteArray) {
            transmissions++
            sent += link to frame
        }

        fun send(dst: Int, type: Int = PacketType.TEXT, ttl: Int = MeshPacket.DEFAULT_TTL,
                 msgId: Long = Random.nextLong(1, 0xFFFFFFFFL), everywhere: Boolean = false): Pair<MeshPacket, MeshRoute> {
            val p = MeshPacket(msgId, id, dst, ttl, type, "hello".toByteArray())
            return p to router.originate(p, everywhere)
        }

        fun onFrame(frame: ByteArray, from: Link) {
            val r = router.receive(frame, from) ?: return
            val p = r.packet
            if (p.dstId == id || p.isBroadcast) {
                delivered[p.key] = (delivered[p.key] ?: 0) + 1
                if (p.type == PacketType.TEXT && p.dstId == id) {
                    router.originate(MeshPacket(Random.nextLong(1, 0xFFFFFFFFL), id, p.srcId,
                        MeshPacket.DEFAULT_TTL, PacketType.ACK, PacketCodec.ackPayload(p.msgId)))
                }
                if (p.type == PacketType.ACK && p.dstId == id) {
                    PacketCodec.parseAck(p.payload)?.let { acked += it }
                }
            }
        }
    }

    // --- ESP32 : transcription de handlePacket() du firmware LoRaMesh.ino ------------
    inner class Esp(val id: Int) {
        val phones = linkedSetOf<Int>()
        val loraPeers = linkedSetOf<Int>()
        private val seenIds = ArrayDeque<Long>()          // le firmware déduplique sur msg_id seul
        private val bleNeighbors = mutableSetOf<Int>()    // alimenté par les HELLO reçus en BLE

        fun onFrame(frame: ByteArray, fromBle: Boolean) {
            val p = PacketCodec.decode(frame) ?: return
            if (p.srcId == id) return
            if (p.msgId in seenIds) return
            seenIds.addLast(p.msgId)
            if (seenIds.size > 100) seenIds.removeFirst()

            if (p.type == PacketType.HELLO) {
                if (fromBle) bleNeighbors += p.srcId
                toPhones(frame) // HELLO LoRa et BLE renvoyés aux téléphones (BLE_FORWARD_TO_PHONES = 1)
                return
            }
            val forMe = p.dstId == id
            val bcast = p.isBroadcast
            val bleLocal = !bcast && !forMe && p.dstId in bleNeighbors
            if (!fromBle) toPhones(frame)
            else if (bcast || bleLocal) toPhones(frame)
            if (forMe || bleLocal) return
            if (p.ttl > 0) {
                val out = PacketCodec.encode(p.withTtl(p.ttl - 1))
                transmissions++
                loraPeers.forEach { queue += ToEsp(it, out, fromBle = false) }
            }
        }

        /** notify() BLE : tous les téléphones connectés, y compris celui qui vient d'envoyer. */
        private fun toPhones(frame: ByteArray) {
            if (phones.isEmpty()) return
            transmissions++
            phones.forEach { queue += ToPhone(it, frame, Link.Gateway) }
        }
    }
}
