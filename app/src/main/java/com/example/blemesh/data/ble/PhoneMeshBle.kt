package com.example.blemesh.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Liens BLE téléphone ↔ téléphone. Chaque téléphone est à la fois :
 *  - PÉRIPHÉRIQUE (serveur GATT) : il s'annonce avec son ID de nœud; les autres s'y connectent
 *    (liens « entrants »), lui écrivent leurs trames et reçoivent les siennes par notification;
 *  - CENTRAL (client GATT) : il scanne et se connecte lui-même à quelques voisins (liens « sortants »).
 *
 * Règles :
 *  - Plusieurs liens en parallèle (MAX_OUTBOUND sortants + les entrants), pas un seul voisin.
 *  - Pour éviter deux liens par paire, seul le plus PETIT ID se connecte automatiquement
 *    (sauf si le téléphone est isolé). Une connexion directe à la demande ([connectTo]) ignore cette règle.
 *  - Une seule opération GATT à la fois par lien : chaque lien a sa file et attend l'accusé de la
 *    pile Bluetooth (onCharacteristicWrite / onNotificationSent) avant la trame suivante.
 *  - Annonce : UUID du service dans le paquet d'annonce, ID du nœud dans la réponse au scan
 *    (les deux ensemble dépassent les 31 octets d'une annonce BLE classique).
 */
@SuppressLint("MissingPermission")
class PhoneMeshBle(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("A0E10001-7B42-4D3A-9A8E-1C01C0DE0001")
        val PACKET_UUID: UUID = UUID.fromString("A0E10002-7B42-4D3A-9A8E-1C01C0DE0001")
        private const val TAG = "PhoneMeshBle"
        private const val MIN_FRAME = 10
        private const val MAX_FRAME = 240           // en-tête mesh 10 o + payload 230 o
        private const val TARGET_MTU = 247
        private const val MAX_AUTO_OUTBOUND = 3     // liens sortants ouverts automatiquement
        private const val MAX_OUTBOUND = 4          // + 1 place pour une connexion directe à la demande
        private const val VISIBLE_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val RETRY_BACKOFF_MS = 15_000L
        private const val OP_TIMEOUT_MS = 2_000L
        private const val MAINTENANCE_MS = 5_000L
    }

    data class Frame(val bytes: ByteArray, val fromAddress: String)

    /** Téléphone vu au scan (annonce reçue), pas forcément relié. */
    private data class Visible(val address: String, val nodeId: Int, val rssi: Int, val seenAtMs: Long)

    private inner class Peer(val address: String, val outbound: Boolean) {
        @Volatile var nodeId: Int? = null
        @Volatile var gatt: BluetoothGatt? = null                        // lien sortant
        @Volatile var characteristic: BluetoothGattCharacteristic? = null // lien sortant
        @Volatile var device: BluetoothDevice? = null                    // lien entrant
        @Volatile var ready = false
        @Volatile var mtu = 23
        @Volatile var pending: CompletableDeferred<Boolean>? = null
        @Volatile var job: Job? = null
        val queue = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val readySignal = CompletableDeferred<Boolean>()
        val openedAtMs = System.currentTimeMillis()
        val longWrite = ByteArrayOutputStream()                         // écritures longues (MTU faible)
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private val frames = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    val incoming: SharedFlow<Frame> = frames.asSharedFlow()

    private val _linkCount = MutableStateFlow(0)
    /** Nombre de téléphones reliés (prêts à échanger), pour l'affichage. */
    val linkCount: StateFlow<Int> = _linkCount.asStateFlow()

    private val outbound = ConcurrentHashMap<String, Peer>()
    private val inbound = ConcurrentHashMap<String, Peer>()
    private val visible = ConcurrentHashMap<Int, Visible>()
    private val lastFailure = ConcurrentHashMap<String, Long>()
    private val notifyLock = Mutex()

    @Volatile private var nodeId: Int = 0
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var scanning = false
    @Volatile private var started = false
    private var maintenanceJob: Job? = null

    // =========================================================================
    // Démarrage / arrêt
    // =========================================================================
    fun start(id: Int) {
        if (started && nodeId == id) return
        if (started) stop()
        nodeId = id
        val bt = adapter ?: return
        if (!bt.isEnabled) return
        try {
            val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val ch = BluetoothGattCharacteristic(
                PACKET_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            ch.addDescriptor(
                BluetoothGattDescriptor(
                    BleClient.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            svc.addCharacteristic(ch)
            val s = manager.openGattServer(context, serverCallback) ?: return
            server = s
            if (!s.addService(svc)) { s.close(); server = null; return }
            started = true
            maintenanceJob = scope.launch { maintenance() }
        } catch (e: SecurityException) {
            Log.w(TAG, "Autorisation Bluetooth manquante", e)
        }
    }

    fun stop() {
        started = false
        maintenanceJob?.cancel(); maintenanceJob = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) { }
        scanning = false
        (outbound.values + inbound.values).forEach { closePeer(it) }
        visible.clear()
        try { server?.close() } catch (_: Exception) { }
        server = null
        publishLinks()
    }

    // =========================================================================
    // API utilisée par le routeur
    // =========================================================================

    /** Adresse d'un lien prêt vers [target], ou null. Lien sortant préféré. */
    fun linkTo(target: Int): String? =
        outbound.values.firstOrNull { it.ready && it.nodeId == target }?.address
            ?: inbound.values.firstOrNull { it.ready && it.nodeId == target }?.address

    /** Le téléphone [target] est-il visible au scan (à portée) depuis peu ? */
    fun isVisible(target: Int): Boolean =
        visible[target]?.let { System.currentTimeMillis() - it.seenAtMs < VISIBLE_TIMEOUT_MS } == true

    /** Met une trame en file sur un lien précis. */
    fun sendTo(address: String, bytes: ByteArray): Boolean {
        if (bytes.size !in MIN_FRAME..MAX_FRAME) return false
        val peer = outbound[address]?.takeIf { it.ready } ?: inbound[address]?.takeIf { it.ready } ?: return false
        return enqueue(peer, bytes)
    }

    /** Envoie sur tous les liens prêts sauf celui (ou ceux du même nœud) d'où vient la trame. */
    fun flood(bytes: ByteArray, exceptAddress: String?): Int {
        if (bytes.size !in MIN_FRAME..MAX_FRAME) return 0
        val exceptNode = exceptAddress?.let { (outbound[it] ?: inbound[it])?.nodeId }
        val readyOut = outbound.values.filter { it.ready }
        val outAddresses = readyOut.map { it.address }.toSet()
        val targets = readyOut + inbound.values.filter { it.ready && it.address !in outAddresses }
        var count = 0
        for (peer in targets) {
            if (peer.address == exceptAddress) continue
            if (exceptNode != null && peer.nodeId == exceptNode) continue
            if (enqueue(peer, bytes)) count++
        }
        return count
    }

    /**
     * Ouvre si possible un lien direct vers [target] (vu au scan mais pas encore relié).
     * Libère au besoin le plus ancien lien sortant. Attend au plus [timeoutMs].
     */
    suspend fun connectTo(target: Int, timeoutMs: Long): Boolean {
        if (linkTo(target) != null) return true
        val v = visible[target]?.takeIf { System.currentTimeMillis() - it.seenAtMs < VISIBLE_TIMEOUT_MS }
            ?: return false
        val peer = outbound.values.firstOrNull { it.nodeId == target } ?: run {
            if (outbound.size >= MAX_OUTBOUND) {
                outbound.values.filter { it.nodeId != target }.minByOrNull { it.openedAtMs }?.let { closePeer(it) }
            }
            openOutbound(v)
        } ?: return false
        return withTimeoutOrNull(timeoutMs) { peer.readySignal.await() } == true
    }

    /** Le routeur a reçu un HELLO sur ce lien : on sait désormais quel nœud se trouve derrière. */
    fun registerPeer(address: String, peerNodeId: Int) {
        outbound[address]?.nodeId = peerNodeId
        inbound[address]?.nodeId = peerNodeId
    }

    // =========================================================================
    // Files d'envoi (une opération GATT à la fois par lien)
    // =========================================================================
    private fun enqueue(peer: Peer, bytes: ByteArray): Boolean {
        if (!peer.outbound && bytes.size > peer.mtu - 3) return false // notification trop grande pour ce MTU
        return peer.queue.trySend(bytes.copyOf()).isSuccess
    }

    private fun startSender(peer: Peer) {
        if (peer.job != null) return
        peer.job = scope.launch {
            for (bytes in peer.queue) {
                if (!peer.ready) continue
                if (peer.outbound) writeOutbound(peer, bytes) else notifyInbound(peer, bytes)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeOutbound(peer: Peer, bytes: ByteArray) {
        val g = peer.gatt ?: return
        val ch = peer.characteristic ?: return
        val done = CompletableDeferred<Boolean>()
        peer.pending = done
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Écriture impossible vers ${peer.address}", e); false
        }
        if (started) withTimeoutOrNull(OP_TIMEOUT_MS) { done.await() }
        peer.pending = null
    }

    @Suppress("DEPRECATION")
    private suspend fun notifyInbound(peer: Peer, bytes: ByteArray) {
        val s = server ?: return
        val device = peer.device ?: return
        val ch = s.getService(SERVICE_UUID)?.getCharacteristic(PACKET_UUID) ?: return
        // Avant Android 13, la valeur est portée par l'objet caractéristique partagé : on sérialise tout.
        notifyLock.withLock {
            val done = CompletableDeferred<Boolean>()
            peer.pending = done
            val started = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    s.notifyCharacteristicChanged(device, ch, false, bytes) == BluetoothStatusCodes.SUCCESS
                } else {
                    ch.value = bytes
                    s.notifyCharacteristicChanged(device, ch, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notification impossible vers ${peer.address}", e); false
            }
            if (started) withTimeoutOrNull(OP_TIMEOUT_MS) { done.await() }
            peer.pending = null
        }
    }

    // =========================================================================
    // Gestion des liens
    // =========================================================================
    private fun openOutbound(v: Visible): Peer? {
        outbound[v.address]?.let { return it }
        val device = try { adapter?.getRemoteDevice(v.address) } catch (_: IllegalArgumentException) { null }
            ?: return null
        val peer = Peer(v.address, outbound = true).also { it.nodeId = v.nodeId }
        outbound[v.address] = peer
        try {
            peer.gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "Connexion impossible à ${v.address}", e)
            closePeer(peer, failed = true)
            return null
        }
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!peer.ready && outbound[peer.address] === peer) closePeer(peer, failed = true)
        }
        return peer
    }

    private fun closePeer(peer: Peer, failed: Boolean = false) {
        val map = if (peer.outbound) outbound else inbound
        map.remove(peer.address, peer)
        if (failed) lastFailure[peer.address] = System.currentTimeMillis()
        peer.ready = false
        peer.job?.cancel()
        peer.queue.close()
        peer.pending?.complete(false)
        peer.readySignal.complete(false)
        peer.gatt?.let { g ->
            try { g.disconnect() } catch (_: Exception) { }
            try { g.close() } catch (_: Exception) { }
        }
        peer.gatt = null
        publishLinks()
    }

    private fun markReady(peer: Peer) {
        peer.ready = true
        peer.readySignal.complete(true)
        startSender(peer)
        publishLinks()
    }

    private fun publishLinks() {
        val nodes = (outbound.values + inbound.values).filter { it.ready }
            .map { it.nodeId ?: it.address.hashCode() }.toSet()
        _linkCount.value = nodes.size
    }

    private fun maybeAutoConnect(v: Visible) {
        if (!started || v.nodeId == nodeId) return
        if (linkTo(v.nodeId) != null || outbound.values.any { it.nodeId == v.nodeId }) return
        if (inbound.values.any { it.nodeId == v.nodeId }) return
        val failedAt = lastFailure[v.address]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_BACKOFF_MS) return
        val isolated = outbound.isEmpty() && inbound.values.none { it.ready }
        if (!isolated && v.nodeId < nodeId) return    // c'est à l'autre (ID plus petit) d'initier
        if (outbound.size >= MAX_AUTO_OUTBOUND) return
        openOutbound(v)
    }

    private suspend fun maintenance() {
        while (scope.isActive && started) {
            delay(MAINTENANCE_MS)
            val now = System.currentTimeMillis()
            visible.entries.removeAll { now - it.value.seenAtMs > VISIBLE_TIMEOUT_MS }
            if (!scanning && server != null) startScan()
        }
    }

    // =========================================================================
    // Annonce et scan
    // =========================================================================
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Annonce BLE refusée (code $errorCode)")
        }
    }

    private fun advertiseAndScan() {
        val bt = adapter ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        // Paquet d'annonce : 3 o (flags) + 18 o (UUID 128 bits) = 21 o ≤ 31
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        // Réponse au scan : 20 o (UUID 128 bits + ID 2 o, little-endian) ≤ 31
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(
                ParcelUuid(SERVICE_UUID),
                byteArrayOf((nodeId and 0xff).toByte(), ((nodeId shr 8) and 0xff).toByte())
            )
            .build()
        try {
            bt.bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Annonce BLE impossible", e)
        }
        startScan()
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                scanCallback
            )
            scanning = true
        } catch (e: Exception) {
            Log.w(TAG, "Scan BLE impossible", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // L'ID arrive dans la réponse au scan : on attend un résultat qui la contient.
            val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            if (bytes.size < 2) return
            val remoteId = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
            if (remoteId == nodeId) return
            val v = Visible(result.device.address, remoteId, result.rssi, System.currentTimeMillis())
            visible[remoteId] = v
            maybeAutoConnect(v)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan BLE en échec (code $errorCode), nouvel essai sous peu")
            scanning = false
        }
    }

    // =========================================================================
    // Serveur GATT : liens entrants
    // =========================================================================
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == SERVICE_UUID) advertiseAndScan()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Android signale ici aussi nos propres connexions sortantes : le lien entrant
                // ne devient « prêt » que lorsque le central active les notifications (CCCD).
                inbound.getOrPut(device.address) { Peer(device.address, outbound = false) }.device = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                inbound[device.address]?.let { closePeer(it) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            inbound[device.address]?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (characteristic.uuid != PACKET_UUID) {
                status = BluetoothGatt.GATT_FAILURE
            } else if (preparedWrite) {
                // Écriture longue (MTU non négocié) : on assemble jusqu'à onExecuteWrite
                val peer = inbound.getOrPut(device.address) { Peer(device.address, outbound = false) }
                val accepted = synchronized(peer.longWrite) {
                    if (offset == 0) peer.longWrite.reset()
                    val fits = offset == peer.longWrite.size() && offset + value.size <= MAX_FRAME
                    if (fits) peer.longWrite.write(value, 0, value.size)
                    fits
                }
                if (!accepted) status = BluetoothGatt.GATT_INVALID_OFFSET
            } else if (offset == 0 && value.size in MIN_FRAME..MAX_FRAME) {
                frames.tryEmit(Frame(value.copyOf(), device.address))
            }
            if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            inbound[device.address]?.let { peer ->
                val assembled = synchronized(peer.longWrite) {
                    val b = peer.longWrite.toByteArray(); peer.longWrite.reset(); b
                }
                if (execute && assembled.size in MIN_FRAME..MAX_FRAME) {
                    frames.tryEmit(Frame(assembled, device.address))
                }
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            val isCccd = descriptor.uuid == BleClient.CCCD_UUID && offset == 0
            val enable = isCccd && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = isCccd && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            if (enable) {
                val peer = inbound.getOrPut(device.address) { Peer(device.address, outbound = false) }
                peer.device = device
                markReady(peer)
            } else if (disable) {
                inbound[device.address]?.let { it.ready = false; publishLinks() }
            }
            val status = if (enable || disable) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            if (responseNeeded) server?.sendResponse(device, requestId, status, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            inbound[device.address]?.pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // =========================================================================
    // Client GATT : liens sortants
    // =========================================================================
    private val clientCallback = object : BluetoothGattCallback() {
        /** Lien sortant correspondant à ce BluetoothGatt (null si abandonné entre-temps). */
        private fun peerOf(gatt: BluetoothGatt): Peer? =
            outbound[gatt.device.address]?.takeIf { it.gatt == null || it.gatt === gatt }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val peer = peerOf(gatt)
            if (peer == null) {
                // Lien déjà abandonné (délai dépassé, remplacé) : on libère la connexion
                try { gatt.close() } catch (_: Exception) { }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.requestMtu(TARGET_MTU)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                closePeer(peer, failed = !peer.ready)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            peerOf(gatt)?.let { if (status == BluetoothGatt.GATT_SUCCESS) it.mtu = mtu }
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val peer = peerOf(gatt) ?: return
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(PACKET_UUID)
            val cccd = ch?.getDescriptor(BleClient.CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) {
                closePeer(peer, failed = true); return
            }
            peer.characteristic = ch
            gatt.setCharacteristicNotification(ch, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val peer = peerOf(gatt) ?: return
            if (descriptor.uuid != BleClient.CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) markReady(peer) else closePeer(peer, failed = true)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            peerOf(gatt)?.pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+ : la valeur est fournie directement
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == PACKET_UUID && value.size in MIN_FRAME..MAX_FRAME) {
                frames.tryEmit(Frame(value.copyOf(), gatt.device.address))
            }
        }

        // Android 12 et moins
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // évite le doublon
            if (characteristic.uuid != PACKET_UUID) return
            characteristic.value?.let { value ->
                if (value.size in MIN_FRAME..MAX_FRAME) frames.tryEmit(Frame(value.copyOf(), gatt.device.address))
            }
        }
    }
}
