package com.example.blemesh.data.mesh

import com.example.blemesh.data.protocol.PacketCodec
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.MeshRoute
import com.example.blemesh.domain.model.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterTest {

    private val bcast = MeshPacket.BROADCAST_ID

    // --- Priorités d'émission -----------------------------------------------------

    @Test
    fun origin_prefers_direct_neighbor_over_gateway() {
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1002)
        sim.bleLink(0x1001, 0x1003)
        sim.attachGateway(0x1001, 0x0001)
        val (_, route) = sim.phone(0x1001).send(0x1002)
        assertEquals(MeshRoute.DIRECT, route)
        assertEquals(listOf(sim.addressOf(0x1002)), sim.phone(0x1001).sent.map { it.first })
    }

    @Test
    fun origin_uses_gateway_when_destination_not_direct() {
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1003)
        sim.attachGateway(0x1001, 0x0001)
        val (_, route) = sim.phone(0x1001).send(0x1009)
        assertEquals(MeshRoute.GATEWAY, route)
        assertEquals(listOf("gateway"), sim.phone(0x1001).sent.map { it.first })
    }

    @Test
    fun origin_floods_phones_without_direct_link_or_gateway() {
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1003)
        sim.bleLink(0x1001, 0x1004)
        val (_, route) = sim.phone(0x1001).send(0x1009)
        assertEquals(MeshRoute.FLOOD, route)
        assertEquals(2, sim.phone(0x1001).sent.size)
    }

    @Test
    fun origin_without_any_link_reports_none() {
        val sim = MeshSimulation()
        val (_, route) = sim.phone(0x1001).send(0x1002)
        assertEquals(MeshRoute.NONE, route)
    }

    // --- Relais par des téléphones non destinataires ----------------------------------

    @Test
    fun line_relay_delivers_once_and_ack_comes_back() {
        // A ── B ── C : A et C hors de portée, B relaie
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1002)
        sim.bleLink(0x1002, 0x1003)
        val (p, _) = sim.phone(0x1001).send(0x1003)
        sim.run()
        assertEquals(1, sim.phone(0x1003).delivered[p.key])
        assertNull("B n'est pas destinataire : rien n'est affiché chez lui", sim.phone(0x1002).delivered[p.key])
        assertTrue("ACK reçu par A", p.msgId in sim.phone(0x1001).acked)
    }

    @Test
    fun relay_never_sends_back_to_the_link_it_came_from() {
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1002)
        sim.bleLink(0x1002, 0x1003)
        val (p, _) = sim.phone(0x1001).send(bcast)
        sim.run()
        val relayedByB = sim.phone(0x1002).sent.filter { PacketCodec.decode(it.second)!!.key == p.key }
        assertEquals(listOf(sim.addressOf(0x1003)), relayedByB.map { it.first })
        assertEquals("TTL décrémenté au relais", MeshPacket.DEFAULT_TTL - 1,
            PacketCodec.decode(relayedByB.single().second)!!.ttl)
    }

    @Test
    fun relay_uses_direct_link_when_destination_is_its_neighbor() {
        // A relié à B; B relié à C, D, E : B doit remettre à D seul, pas inonder
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1002)
        listOf(0x1003, 0x1004, 0x1005).forEach { sim.bleLink(0x1002, it) }
        val (p, _) = sim.phone(0x1001).send(0x1004)
        sim.run()
        val relayedByB = sim.phone(0x1002).sent.filter { PacketCodec.decode(it.second)!!.key == p.key }
        assertEquals(listOf(sim.addressOf(0x1004)), relayedByB.map { it.first })
    }

    // --- Absence de boucle ------------------------------------------------------------

    @Test
    fun ring_broadcast_is_delivered_exactly_once_everywhere() {
        val sim = MeshSimulation()
        val ids = (0x1001..0x1008).toList()
        ids.zipWithNext().forEach { (a, b) -> sim.bleLink(a, b) }
        sim.bleLink(ids.last(), ids.first()) // anneau : un cycle
        val (p, _) = sim.phone(ids[0]).send(bcast)
        sim.run()
        // TTL 5 sur un anneau de 8 : tout le monde est à ≤ 4 sauts
        ids.drop(1).forEach { assertEquals("nœud %04X".format(it), 1, sim.phone(it).delivered[p.key]) }
        assertTrue("chaque nœud émet au plus une fois par lien", sim.transmissions <= ids.size * 2)
    }

    @Test
    fun fully_connected_mesh_with_unknown_destination_terminates() {
        // Graphe complet de 7 téléphones = cycles partout; destinataire absent du réseau
        val sim = MeshSimulation()
        val ids = (0x1001..0x1007).toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) sim.bleLink(ids[i], ids[j])
        sim.phone(ids[0]).send(0x7777)
        sim.run()
        // Chaque téléphone relaie au plus une fois vers ses 5 autres voisins (6 pour l'émetteur)
        assertTrue("transmissions = ${sim.transmissions}", sim.transmissions <= 6 + 6 * 5)
    }

    @Test
    fun duplicate_arriving_on_two_links_is_dropped() {
        val sim = MeshSimulation()
        val router = sim.phone(0x1002).router
        val frame = PacketCodec.encode(MeshPacket(42, 0x1001, bcast, 5, PacketType.TEXT, ByteArray(1)))
        assertTrue(router.receive(frame, Link.Phone("a")) != null)
        assertNull(router.receive(frame, Link.Phone("b")))
        assertNull(router.receive(frame, Link.Gateway))
    }

    @Test
    fun own_echo_is_dropped() {
        val sim = MeshSimulation()
        val frame = PacketCodec.encode(MeshPacket(7, 0x1002, bcast, 5, PacketType.TEXT, ByteArray(1)))
        assertNull(sim.phone(0x1002).router.receive(frame, Link.Gateway))
    }

    @Test
    fun ttl_bounds_the_number_of_hops() {
        // Chaîne de 9 téléphones, diffusion TTL 5 : portée 5 sauts
        val sim = MeshSimulation()
        val ids = (0x1001..0x1009).toList()
        ids.zipWithNext().forEach { (a, b) -> sim.bleLink(a, b) }
        val (p, _) = sim.phone(ids[0]).send(bcast, ttl = 5)
        sim.run()
        (1..5).forEach { assertEquals(1, sim.phone(ids[it]).delivered[p.key]) }
        (6..8).forEach { assertNull("saut $it hors TTL", sim.phone(ids[it]).delivered[p.key]) }
    }

    @Test
    fun hello_is_never_relayed() {
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1002)
        sim.bleLink(0x1002, 0x1003)
        val (p, _) = sim.phone(0x1001).send(bcast, type = PacketType.HELLO, ttl = 1)
        sim.run()
        assertEquals(1, sim.phone(0x1002).delivered[p.key])
        assertNull(sim.phone(0x1003).delivered[p.key])
    }

    // --- Interopérabilité avec le firmware ESP32 ------------------------------------------

    @Test
    fun phone_gateway_lora_gateway_phone_roundtrip() {
        // A ─BLE─ ESP1 ~~LoRa~~ ESP2 ─BLE─ B
        val sim = MeshSimulation()
        sim.attachGateway(0x1001, 0x0001)
        sim.attachGateway(0x1002, 0x0002)
        sim.loraLink(0x0001, 0x0002)
        val (p, route) = sim.phone(0x1001).send(0x1002)
        assertEquals(MeshRoute.GATEWAY, route)
        sim.run()
        assertEquals(1, sim.phone(0x1002).delivered[p.key])
        assertTrue(p.msgId in sim.phone(0x1001).acked)
    }

    @Test
    fun phone_without_gateway_reaches_lora_through_a_relay_phone() {
        // A ─BLE─ C ─BLE─ ESP1 ~~LoRa~~ ESP2 ─BLE─ B ; A n'a ni lien direct vers B ni passerelle
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1003)
        sim.attachGateway(0x1003, 0x0001)
        sim.attachGateway(0x1002, 0x0002)
        sim.loraLink(0x0001, 0x0002)
        val (p, route) = sim.phone(0x1001).send(0x1002)
        assertEquals(MeshRoute.FLOOD, route)
        sim.run()
        assertEquals(1, sim.phone(0x1002).delivered[p.key])
        assertNull(sim.phone(0x1003).delivered[p.key])
        assertTrue("ACK revenu par LoRa puis par C", p.msgId in sim.phone(0x1001).acked)
    }

    @Test
    fun mixed_topology_with_cycles_through_gateways_terminates() {
        // Deux îlots de téléphones reliés entre eux ET par LoRa : plusieurs cycles BLE + LoRa
        val sim = MeshSimulation()
        val left = listOf(0x1001, 0x1002, 0x1003)
        val right = listOf(0x2001, 0x2002, 0x2003)
        for (g in listOf(left, right)) for (i in g.indices) for (j in i + 1 until g.size) sim.bleLink(g[i], g[j])
        sim.bleLink(0x1003, 0x2001)
        left.forEach { sim.attachGateway(it, 0x0001) }
        right.forEach { sim.attachGateway(it, 0x0002) }
        sim.loraLink(0x0001, 0x0002)
        val (p, _) = sim.phone(0x1001).send(bcast)
        sim.run()
        (left + right).drop(1).forEach { assertEquals(1, sim.phone(it).delivered[p.key]) }
    }

    @Test
    fun retry_everywhere_is_absorbed_by_dedup() {
        val sim = MeshSimulation()
        sim.bleLink(0x1001, 0x1002)
        sim.bleLink(0x1002, 0x1003)
        val (p, _) = sim.phone(0x1001).send(0x1003, msgId = 99)
        sim.run()
        val before = sim.transmissions
        // Nouvelle tentative, même msg_id : B l'a déjà vu et la jette
        sim.phone(0x1001).router.originate(p, everywhere = true)
        sim.run()
        assertEquals(before + 1, sim.transmissions)
        assertEquals(1, sim.phone(0x1003).delivered[p.key])
    }

    // --- Cache anti-doublons -----------------------------------------------------------

    @Test
    fun seen_cache_expires_and_is_bounded() {
        var now = 0L
        val cache = SeenCache(capacity = 3, ttlMs = 100, clock = { now })
        assertTrue(cache.add(1)); assertFalse(cache.add(1))
        cache.add(2); cache.add(3); cache.add(4)          // capacité 3 : la clé 1 sort
        assertEquals(3, cache.size)
        assertTrue(cache.add(1))
        now = 1_000                                       // tout a expiré
        assertTrue(cache.add(2))
        assertEquals(1, cache.size)
    }

    @Test
    fun message_key_distinguishes_senders_with_same_msg_id() {
        assertFalse(MeshPacket.messageKey(0x1001, 5) == MeshPacket.messageKey(0x1002, 5))
        assertEquals(MeshPacket.messageKey(0xFFFF, 0xFFFFFFFFL), 0xFFFF_FFFFFFFFL)
    }
}
