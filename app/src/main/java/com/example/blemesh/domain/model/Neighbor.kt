package com.example.blemesh.domain.model

/**
 * Nœud entendu directement (paquet HELLO reçu).
 * [viaGateway] = HELLO transmis par l'ESP32 (voisin LoRa ou autre téléphone de la même passerelle);
 * false = voisin BLE direct, téléphone à téléphone.
 */
data class Neighbor(val nodeId: Int, val lastSeenMs: Long, val viaGateway: Boolean = false)
