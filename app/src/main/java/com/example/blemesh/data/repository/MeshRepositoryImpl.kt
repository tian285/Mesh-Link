package com.example.blemesh.data.repository

import com.example.blemesh.data.ble.BleClient
import com.example.blemesh.data.ble.PhoneMeshBle
import com.example.blemesh.data.local.ContactStore
import com.example.blemesh.data.local.NodeIdStore
import com.example.blemesh.data.local.ProfileStore
import com.example.blemesh.data.mesh.Link
import com.example.blemesh.data.mesh.MeshRouter
import com.example.blemesh.data.mesh.MeshTransport
import com.example.blemesh.data.protocol.PacketCodec
import com.example.blemesh.domain.model.ChatMessage
import com.example.blemesh.domain.model.ConnectionState
import com.example.blemesh.domain.model.Contact
import com.example.blemesh.domain.model.DeliveryStatus
import com.example.blemesh.domain.model.GatewayDevice
import com.example.blemesh.domain.model.GpsPosition
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.MeshRoute
import com.example.blemesh.domain.model.Neighbor
import com.example.blemesh.domain.model.NodeId
import com.example.blemesh.domain.model.PacketType
import com.example.blemesh.domain.model.Pseudo
import com.example.blemesh.domain.repository.MeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Logique applicative du mesh côté téléphone :
 *  - routage et anti-boucle délégués à [MeshRouter] (direct → passerelle → inondation, TTL, doublons);
 *  - HELLO périodique (voisins directs) portant le pseudo, annonce NAME périodique (tout le réseau);
 *  - annuaire des contacts (pseudo annoncé + alias local);
 *  - ACK automatique des messages qui me sont destinés, nouvelles tentatives sinon.
 */
class MeshRepositoryImpl(
    private val ble: BleClient,
    private val phoneBle: PhoneMeshBle,
    private val nodeIdStore: NodeIdStore,
    private val profileStore: ProfileStore,
    private val contactStore: ContactStore,
    private val scope: CoroutineScope
) : MeshRepository {

    private companion object {
        const val HELLO_INTERVAL_MS = 20_000L
        const val NAME_INTERVAL_MS = 5 * 60_000L
        const val NAME_FIRST_DELAY_MS = 3_000L
        const val NEIGHBOR_TIMEOUT_MS = 60_000L
        const val PRUNE_INTERVAL_MS = 5_000L
        const val CONTACT_SAVE_INTERVAL_MS = 30_000L
        const val GATEWAY_RESCAN_MS = 120_000L
        const val RETRY_DELAY_MS = 20_000L
        const val RETRIES = 2
        const val LAST_ACK_WAIT_MS = 30_000L
        const val DIRECT_CONNECT_TIMEOUT_MS = 4_000L
    }

    // --- État exposé ------------------------------------------------------------
    private val _myNodeId = MutableStateFlow(nodeIdStore.load())
    override val myNodeId: StateFlow<Int> = _myNodeId.asStateFlow()

    private val _myPseudo = MutableStateFlow(profileStore.loadPseudo() ?: Pseudo.default(_myNodeId.value))
    override val myPseudo: StateFlow<String> = _myPseudo.asStateFlow()

    override val connectionState: StateFlow<ConnectionState> = ble.connectionState
    override val scanResults: StateFlow<List<GatewayDevice>> = ble.scanResults
    override val isScanning: StateFlow<Boolean> = ble.isScanning
    override val phoneLinks: StateFlow<Int> = phoneBle.linkCount

    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    override val neighbors: StateFlow<List<Neighbor>> = _neighbors.asStateFlow()

    private val _contacts = MutableStateFlow(contactStore.load())
    override val contacts: StateFlow<Map<Int, Contact>> = _contacts.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _positions = MutableStateFlow<Map<Int, GpsPosition>>(emptyMap())
    override val positions: StateFlow<Map<Int, GpsPosition>> = _positions.asStateFlow()

    // --- Routage ----------------------------------------------------------------
    private val transport = object : MeshTransport {
        override fun phoneLinkTo(nodeId: Int): String? = phoneBle.linkTo(nodeId)
        override fun sendToGateway(frame: ByteArray): Boolean = ble.send(frame)
        override fun sendToPhone(address: String, frame: ByteArray): Boolean = phoneBle.sendTo(address, frame)
        override fun floodPhones(frame: ByteArray, exceptAddress: String?): Int = phoneBle.flood(frame, exceptAddress)
    }
    private val router = MeshRouter(transport, { _myNodeId.value })

    private var helloJob: Job? = null
    private var nameJob: Job? = null
    @Volatile private var userDisconnected = false
    @Volatile private var contactsDirty = false

    init {
        scope.launch { ble.incoming.collect { onFrame(it, Link.Gateway) } }
        scope.launch { phoneBle.incoming.collect { onFrame(it.bytes, Link.Phone(it.fromAddress)) } }

        // Nouveau lien (téléphone ou passerelle) : on se présente tout de suite, sans attendre 20 s.
        scope.launch {
            var previous = 0
            phoneBle.linkCount.collect { n -> if (n > previous) sendHello(); previous = n }
        }
        scope.launch {
            ble.connectionState.collect { if (it is ConnectionState.Connected) { sendHello(); sendName() } }
        }

        // Ménage périodique : voisins silencieux, sauvegarde de l'annuaire
        scope.launch {
            var lastSave = 0L
            while (true) {
                delay(PRUNE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                _neighbors.update { list -> list.filter { now - it.lastSeenMs <= NEIGHBOR_TIMEOUT_MS } }
                if (contactsDirty && now - lastSave > CONTACT_SAVE_INTERVAL_MS) {
                    saveContacts(); lastSave = now
                }
            }
        }
    }

    // --- Connexion passerelle (délégation) --------------------------------------
    override fun startScan() = ble.startScan()
    override fun stopScan() = ble.stopScan()
    override fun connect(address: String) { userDisconnected = false; ble.connect(address) }
    override fun disconnect() { userDisconnected = true; ble.disconnect() }

    override fun startPhoneMesh() {
        phoneBle.start(_myNodeId.value)
        if (!userDisconnected) ble.startAutoScan()
        if (helloJob?.isActive != true) {
            helloJob = scope.launch {
                var sinceRescan = 0L
                while (true) {
                    sendHello()
                    delay(HELLO_INTERVAL_MS)
                    sinceRescan += HELLO_INTERVAL_MS
                    // Passerelle allumée plus tard : nouvelle recherche automatique de temps en temps
                    if (sinceRescan >= GATEWAY_RESCAN_MS && !userDisconnected &&
                        ble.connectionState.value !is ConnectionState.Connected) {
                        sinceRescan = 0
                        ble.startAutoScan()
                    }
                }
            }
        }
        if (nameJob?.isActive != true) {
            nameJob = scope.launch {
                delay(NAME_FIRST_DELAY_MS)
                while (true) {
                    sendName()
                    delay(NAME_INTERVAL_MS)
                }
            }
        }
    }

    // --- Envoi ------------------------------------------------------------------
    override suspend fun sendText(dstId: Int, text: String): Result<Unit> {
        val payload = PacketCodec.textPayload(text)
        if (payload.size > MeshPacket.MAX_PAYLOAD) {
            return fail("Message trop long (maximum ${MeshPacket.MAX_PAYLOAD} octets)")
        }
        // Étape 1 : le destinataire est à portée BLE mais pas encore relié → on ouvre un lien direct.
        tryDirectLink(dstId)

        val me = _myNodeId.value
        val packet = MeshPacket(newMsgId(), me, dstId, MeshPacket.DEFAULT_TTL, PacketType.TEXT, payload)
        val message = ChatMessage(
            msgId = packet.msgId, isFromMe = true, srcId = me, dstId = dstId, text = text,
            timestampMs = System.currentTimeMillis(), status = DeliveryStatus.SENT
        )
        _messages.update { it + message } // avant l'envoi : un ACK très rapide trouve déjà le message

        // Étapes 2 et 3 : direct, sinon passerelle, sinon inondation (voir MeshRouter)
        val route = router.originate(packet)
        if (route == MeshRoute.NONE) {
            _messages.update { list -> list.filterNot { it.isFromMe && it.msgId == packet.msgId } }
            router.seen.remove(packet.key)
            return fail("Envoi impossible : aucun téléphone ni passerelle à portée")
        }
        updateMessage(packet.msgId) { it.copy(route = route) }
        if (!packet.isBroadcast) scope.launch { retryUntilAck(packet) }
        return Result.success(Unit)
    }

    /** Nouvelles tentatives avec le MÊME (src_id, msg_id) : les relais qui l'ont déjà vu l'ignorent. */
    private suspend fun retryUntilAck(packet: MeshPacket) {
        repeat(RETRIES) {
            delay(RETRY_DELAY_MS)
            if (!waitingAck(packet.msgId)) return
            tryDirectLink(packet.dstId)
            val route = router.originate(packet, everywhere = true)
            if (route != MeshRoute.NONE) updateMessage(packet.msgId) { it.copy(route = route) }
        }
        delay(LAST_ACK_WAIT_MS)
        updateMessage(packet.msgId) { if (it.status == DeliveryStatus.SENT) it.copy(status = DeliveryStatus.NO_ACK) else it }
    }

    private suspend fun tryDirectLink(dstId: Int) {
        if (dstId == MeshPacket.BROADCAST_ID) return
        if (phoneBle.linkTo(dstId) == null && phoneBle.isVisible(dstId)) {
            phoneBle.connectTo(dstId, DIRECT_CONNECT_TIMEOUT_MS)
        }
    }

    override suspend fun sendPosition(latitude: Double, longitude: Double): Result<Unit> {
        val me = _myNodeId.value
        val packet = MeshPacket(
            newMsgId(), me, MeshPacket.BROADCAST_ID, MeshPacket.DEFAULT_TTL, PacketType.GPS,
            PacketCodec.gpsPayload(latitude.toFloat(), longitude.toFloat(), _myPseudo.value)
        )
        updateMyPosition(latitude, longitude)
        if (router.originate(packet) == MeshRoute.NONE) {
            return fail("Position enregistrée sur la carte, mais aucun téléphone ni passerelle à portée pour l'envoyer")
        }
        return Result.success(Unit)
    }

    override fun updateMyPosition(latitude: Double, longitude: Double) {
        val me = _myNodeId.value
        _positions.update { it + (me to GpsPosition(me, latitude, longitude, System.currentTimeMillis())) }
    }

    private fun sendHello() {
        router.originate(
            MeshPacket(newMsgId(), _myNodeId.value, MeshPacket.BROADCAST_ID, 1, PacketType.HELLO,
                PacketCodec.pseudoBytes(_myPseudo.value))
        )
    }

    private fun sendName() {
        router.originate(
            MeshPacket(newMsgId(), _myNodeId.value, MeshPacket.BROADCAST_ID, MeshPacket.DEFAULT_TTL,
                PacketType.NAME, PacketCodec.pseudoBytes(_myPseudo.value))
        )
    }

    private fun sendAck(toNode: Int, originalMsgId: Long) {
        router.originate(
            MeshPacket(newMsgId(), _myNodeId.value, toNode, MeshPacket.DEFAULT_TTL, PacketType.ACK,
                PacketCodec.ackPayload(originalMsgId))
        )
    }

    // --- Profil et annuaire ------------------------------------------------------
    override fun setMyNodeId(id: Int): Result<Unit> {
        if (!NodeId.isValid(id)) return Result.failure(IllegalArgumentException("ID hors plage (0001 à FFFE)"))
        nodeIdStore.save(id)
        _myNodeId.value = id
        if (profileStore.loadPseudo() == null) _myPseudo.value = Pseudo.default(id)
        phoneBle.start(id) // relance l'annonce BLE avec le nouvel ID
        return Result.success(Unit)
    }

    override fun setMyPseudo(pseudo: String): Result<Unit> {
        val clean = Pseudo.sanitize(pseudo)
            ?: return Result.failure(IllegalArgumentException("Pseudo vide"))
        profileStore.savePseudo(clean)
        _myPseudo.value = clean
        scope.launch { sendHello(); sendName() } // tout le réseau apprend le nouveau pseudo
        return Result.success(Unit)
    }

    override fun renameContact(nodeId: Int, alias: String?) {
        val clean = Pseudo.sanitize(alias)
        _contacts.update { map -> map + (nodeId to (map[nodeId] ?: Contact(nodeId)).copy(alias = clean)) }
        saveContacts()
    }

    private fun noteContact(nodeId: Int, now: Long, announced: String?) {
        if (nodeId == _myNodeId.value || nodeId == 0 || nodeId == MeshPacket.BROADCAST_ID) return
        _contacts.update { map ->
            val old = map[nodeId] ?: Contact(nodeId)
            map + (nodeId to old.copy(announcedName = announced ?: old.announcedName, lastSeenMs = now))
        }
        contactsDirty = true
    }

    private fun saveContacts() {
        contactsDirty = false
        contactStore.save(_contacts.value.values)
    }

    // --- Réception ---------------------------------------------------------------
    private fun onFrame(bytes: ByteArray, from: Link) {
        // Doublon, écho, trame invalide → null. Le relais (si besoin) est déjà fait par le routeur.
        val received = router.receive(bytes, from) ?: return
        val packet = received.packet
        val me = _myNodeId.value
        val now = System.currentTimeMillis()
        val forMe = packet.dstId == me || packet.isBroadcast

        // Un HELLO venu d'un téléphone n'est jamais relayé : son émetteur est derrière CE lien.
        if (packet.type == PacketType.HELLO && from is Link.Phone) phoneBle.registerPeer(from.address, packet.srcId)

        val announced = when (packet.type) {
            PacketType.HELLO, PacketType.NAME -> PacketCodec.parsePseudo(packet.payload)
            PacketType.GPS -> PacketCodec.parseGpsPseudo(packet.payload)
            else -> null
        }
        noteContact(packet.srcId, now, announced)

        when (packet.type) {
            PacketType.HELLO -> touchNeighbor(packet.srcId, now, viaGateway = from is Link.Gateway)

            PacketType.TEXT -> if (forMe) {
                _messages.update {
                    it + ChatMessage(
                        msgId = packet.msgId, isFromMe = false, srcId = packet.srcId, dstId = packet.dstId,
                        text = PacketCodec.parseText(packet.payload), timestampMs = now,
                        status = DeliveryStatus.RECEIVED,
                        hops = (MeshPacket.DEFAULT_TTL - packet.ttl + 1).coerceAtLeast(1)
                    )
                }
                if (packet.dstId == me) sendAck(packet.srcId, packet.msgId)
            }

            PacketType.GPS -> if (forMe) {
                PacketCodec.parseGps(packet.payload)?.let { (lat, lon) ->
                    _positions.update {
                        it + (packet.srcId to GpsPosition(packet.srcId, lat.toDouble(), lon.toDouble(), now))
                    }
                }
            }

            PacketType.ACK -> if (packet.dstId == me) {
                val original = PacketCodec.parseAck(packet.payload) ?: return
                updateMessage(original) {
                    if (it.dstId == packet.srcId) it.copy(status = DeliveryStatus.DELIVERED) else it
                }
            }
            // PacketType.NAME : déjà traité par noteContact
        }
    }

    private fun touchNeighbor(nodeId: Int, now: Long, viaGateway: Boolean) {
        _neighbors.update { list ->
            (list.filterNot { it.nodeId == nodeId } + Neighbor(nodeId, now, viaGateway)).sortedBy { it.nodeId }
        }
    }

    // --- Utilitaires -------------------------------------------------------------
    private fun waitingAck(msgId: Long): Boolean =
        _messages.value.any { it.isFromMe && it.msgId == msgId && it.status == DeliveryStatus.SENT }

    private fun updateMessage(msgId: Long, change: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.isFromMe && it.msgId == msgId) change(it) else it } }
    }

    private fun newMsgId(): Long = Random.nextLong(1, 0x1_0000_0000L)

    private fun fail(message: String): Result<Unit> = Result.failure(IllegalStateException(message))
}
