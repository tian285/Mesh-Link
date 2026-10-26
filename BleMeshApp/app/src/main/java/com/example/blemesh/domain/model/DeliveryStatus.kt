package com.example.blemesh.domain.model

enum class DeliveryStatus {
    RECEIVED,   // message reçu d'un autre nœud
    SENT,       // transmis à la passerelle ESP32, en attente d'ACK (accusé de réception)
    DELIVERED,  // ACK reçu : le destinataire a bien reçu le message
    NO_ACK      // aucun ACK reçu dans le délai imparti
}
