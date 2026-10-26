package com.example.blemesh.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    /** gatewayId : ID du nœud ESP32 déduit de son nom BLE (ESP32_Mesh_0xXXXX), si lisible. */
    data class Connected(val deviceName: String, val gatewayId: Int?) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
