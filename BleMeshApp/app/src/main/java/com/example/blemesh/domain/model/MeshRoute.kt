package com.example.blemesh.domain.model

/** Chemin choisi pour émettre ou relayer un paquet. */
enum class MeshRoute {
    /** Le destinataire est un voisin BLE déjà relié : envoi à lui seul. */
    DIRECT,
    /** Remis à la passerelle ESP32 + LoRa connectée. */
    GATEWAY,
    /** Inondation contrôlée vers tous les téléphones reliés (sauf celui d'où vient le paquet). */
    FLOOD,
    /** Passerelle et téléphones à la fois (diffusion, relais, ou nouvelle tentative). */
    GATEWAY_AND_FLOOD,
    /** Aucun lien disponible. */
    NONE
}
