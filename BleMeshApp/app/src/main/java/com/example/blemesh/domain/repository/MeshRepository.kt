package com.example.blemesh.domain.repository

import com.example.blemesh.domain.model.ChatMessage
import com.example.blemesh.domain.model.ConnectionState
import com.example.blemesh.domain.model.Contact
import com.example.blemesh.domain.model.GatewayDevice
import com.example.blemesh.domain.model.GpsPosition
import com.example.blemesh.domain.model.Neighbor
import kotlinx.coroutines.flow.StateFlow

interface MeshRepository {
    val myNodeId: StateFlow<Int>
    val myPseudo: StateFlow<String>
    val connectionState: StateFlow<ConnectionState>
    val scanResults: StateFlow<List<GatewayDevice>>
    val isScanning: StateFlow<Boolean>
    val neighbors: StateFlow<List<Neighbor>>
    val contacts: StateFlow<Map<Int, Contact>>
    /** Nombre de téléphones reliés en BLE direct. */
    val phoneLinks: StateFlow<Int>
    val messages: StateFlow<List<ChatMessage>>
    val positions: StateFlow<Map<Int, GpsPosition>>

    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun startPhoneMesh()

    suspend fun sendText(dstId: Int, text: String): Result<Unit>
    suspend fun sendPosition(latitude: Double, longitude: Double): Result<Unit>
    /** Enregistre ma position pour la carte, sans l'envoyer. */
    fun updateMyPosition(latitude: Double, longitude: Double)

    fun setMyNodeId(id: Int): Result<Unit>
    fun setMyPseudo(pseudo: String): Result<Unit>
    /** Surnom local d'un contact; null ou vide = revenir au pseudo annoncé. */
    fun renameContact(nodeId: Int, alias: String?)
}
