package com.example.blemesh.domain.model

/** Passerelle ESP32 trouvée lors du scan BLE. */
data class GatewayDevice(val name: String, val address: String, val rssi: Int)
