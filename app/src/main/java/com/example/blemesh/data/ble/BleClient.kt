package com.example.blemesh.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.example.blemesh.domain.model.ConnectionState
import com.example.blemesh.domain.model.GatewayDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Client BLE (GATT) vers la passerelle ESP32.
 *  - RX (0002) : le téléphone ÉCRIT les paquets à envoyer
 *  - TX (0003) : l'ESP32 NOTIFIE les paquets reçus
 * Les écritures sont sérialisées (une seule opération GATT à la fois).
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val TARGET_MTU = 247
        const val DEFAULT_MTU = 23
        const val ATT_OVERHEAD = 3
        private const val SCAN_DURATION_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private val GATEWAY_NAME = Regex("""ESP32_Mesh_0x([0-9A-Fa-f]{4})""")
    }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = DEFAULT_MTU
    private val knownNames = ConcurrentHashMap<String, String>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<GatewayDevice>>(emptyList())
    val scanResults: StateFlow<List<GatewayDevice>> = _scanResults.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    /** Trames brutes reçues de l'ESP32 (une trame = un paquet mesh). */
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    /** Taille maximale d'un paquet écrivable avec le MTU (Maximum Transmission Unit) négocié. */
    val maxPacketSize: Int get() = mtu - ATT_OVERHEAD

    // --- Écriture sérialisée ----------------------------------------------------
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null

    init {
        scope.launch {
            for (data in outbox) writeBlocking(data)
        }
    }

    // --- Scan -------------------------------------------------------------------
    private var scanJob: Job? = null
    @Volatile private var autoConnectOnScan = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Passerelle inconnue"
            knownNames[address] = name
            val device = GatewayDevice(name, address, result.rssi)
            _scanResults.update { list ->
                (list.filterNot { it.address == address } + device).sortedByDescending { it.rssi }
            }
            if (autoConnectOnScan && _connectionState.value !is ConnectionState.Connected &&
                _connectionState.value !is ConnectionState.Connecting) {
                autoConnectOnScan = false
                connect(address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _connectionState.value = ConnectionState.Failed("Échec du scan BLE (code $errorCode)")
        }
    }

    fun startScan() {
        startScan(autoConnect = false)
    }

    /** Search and connect to the first available gateway for the automatic mesh route. */
    fun startAutoScan() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) return
        startScan(autoConnect = true)
    }

    private fun startScan(autoConnect: Boolean) {
        val bt = adapter
        val scanner = bt?.bluetoothLeScanner
        if (bt == null || !bt.isEnabled || scanner == null || _isScanning.value) return
        _scanResults.value = emptyList()
        autoConnectOnScan = autoConnect
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        _isScanning.value = true
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    fun stopScan() {
        autoConnectOnScan = false
        scanJob?.cancel()
        if (_isScanning.value) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: IllegalStateException) {
                // Bluetooth coupé entre-temps : rien à arrêter
            }
        }
        _isScanning.value = false
    }

    // --- Connexion --------------------------------------------------------------
    fun connect(address: String) {
        val state = _connectionState.value
        if (state is ConnectionState.Connecting || state is ConnectionState.Connected) return
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            _connectionState.value = ConnectionState.Failed("Appareil introuvable")
            return
        }
        stopScan()
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt ?: return
        g.disconnect()
        // Filet de sécurité si le callback de déconnexion n'arrive pas
        scope.launch {
            delay(2_000)
            if (gatt === g) cleanup(g, ConnectionState.Disconnected)
        }
    }

    private fun cleanup(g: BluetoothGatt, newState: ConnectionState) {
        try {
            g.close()
        } catch (_: Exception) {
        }
        if (gatt === g) gatt = null
        rxChar = null
        mtu = DEFAULT_MTU
        _connectionState.value = newState
    }

    // --- Envoi ------------------------------------------------------------------
    /** Met le paquet en file d'envoi. Retourne false si non connecté ou trop volumineux. */
    fun send(data: ByteArray): Boolean {
        if (_connectionState.value !is ConnectionState.Connected) return false
        if (data.size > maxPacketSize) return false
        return outbox.trySend(data).isSuccess
    }

    private suspend fun writeBlocking(data: ByteArray) {
        val g = gatt ?: return
        val c = rxChar ?: return
        val done = CompletableDeferred<Boolean>()
        pendingWrite = done
        val started = startWrite(g, c, data)
        if (started) withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
        pendingWrite = null
    }

    @Suppress("DEPRECATION")
    private fun startWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
    }

    // --- Callbacks GATT ---------------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    gatt = g
                    // 1) on négocie le MTU, 2) on découvre les services (dans onMtuChanged)
                    if (!g.requestMtu(TARGET_MTU)) g.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val state = if (status == BluetoothGatt.GATT_SUCCESS) {
                        ConnectionState.Disconnected
                    } else {
                        ConnectionState.Failed("Connexion perdue ou refusée (code $status)")
                    }
                    cleanup(g, state)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, negotiatedMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = negotiatedMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_CHAR_UUID)
            val tx = service?.getCharacteristic(TX_CHAR_UUID)
            val cccd = tx?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null || cccd == null) {
                cleanup(g, ConnectionState.Failed("Service mesh introuvable sur cet appareil"))
                return
            }
            rxChar = rx
            g.setCharacteristicNotification(tx, true)
            enableNotifications(g, cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val name = g.device.name ?: knownNames[g.device.address] ?: g.device.address
                val gatewayId = GATEWAY_NAME.find(name)?.groupValues?.get(1)?.toIntOrNull(16)
                _connectionState.value = ConnectionState.Connected(name, gatewayId)
            } else {
                cleanup(g, ConnectionState.Failed("Activation des notifications impossible (code $status)"))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+ : la valeur est fournie directement
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) _incoming.tryEmit(value)
        }

        // Android 12 et moins
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // évite le doublon
            if (characteristic.uuid == TX_CHAR_UUID) {
                characteristic.value?.let { _incoming.tryEmit(it) }
            }
        }
    }
}
