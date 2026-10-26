package com.example.blemesh.data.mesh

/** D'où vient une trame reçue. */
sealed interface Link {
    /** La passerelle ESP32 + LoRa connectée en BLE. */
    data object Gateway : Link
    /** Un autre téléphone relié en BLE, identifié par l'adresse du lien. */
    data class Phone(val address: String) : Link
}

/**
 * Ce dont le routeur a besoin pour émettre. Implémenté par les couches BLE Android en production
 * et par de faux liens dans les tests (simulation de plusieurs téléphones sur la JVM).
 */
interface MeshTransport {
    /** Adresse d'un lien BLE téléphone déjà ouvert vers [nodeId] (voisin direct), ou null. */
    fun phoneLinkTo(nodeId: Int): String?

    /** Envoie à la passerelle ESP32. false si aucune passerelle n'est connectée. */
    fun sendToGateway(frame: ByteArray): Boolean

    /** Envoie sur un seul lien téléphone. */
    fun sendToPhone(address: String, frame: ByteArray): Boolean

    /** Envoie à tous les téléphones reliés sauf [exceptAddress]. Retourne le nombre de liens utilisés. */
    fun floodPhones(frame: ByteArray, exceptAddress: String?): Int
}
