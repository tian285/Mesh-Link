package com.example.blemesh.domain.model

/**
 * Personne (ou ESP32) connue du réseau.
 * - [announcedName] : pseudo choisi par la personne elle-même et reçu par radio.
 * - [alias] : surnom que J'AI donné localement; il a priorité à l'affichage et ne quitte pas ce téléphone.
 */
data class Contact(
    val nodeId: Int,
    val announcedName: String? = null,
    val alias: String? = null,
    val lastSeenMs: Long = 0L
) {
    /** Nom affiché, sans désambiguïsation (voir [DisplayNames]). */
    val baseName: String get() = alias ?: announcedName ?: "Nœud %04X".format(nodeId)
}

/** Résolution ID → nom lisible, avec l'ID ajouté seulement en cas d'homonymie. */
class DisplayNames(contacts: Map<Int, Contact>, private val myNodeId: Int, private val myPseudo: String) {

    private val byId = contacts
    private val duplicated: Set<String> = contacts.values
        .groupBy { it.baseName.lowercase() }
        .filterValues { it.size > 1 }
        .keys

    fun of(nodeId: Int): String = when (nodeId) {
        MeshPacket.BROADCAST_ID -> "Tout le monde"
        myNodeId -> "$myPseudo (moi)"
        else -> {
            val base = byId[nodeId]?.baseName ?: "Nœud %04X".format(nodeId)
            if (base.lowercase() in duplicated) "$base #" + "%04X".format(nodeId) else base
        }
    }

    /** Recherche un contact par pseudo/alias exact (insensible à la casse). Null si absent ou ambigu. */
    fun resolve(name: String): Int? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        val hits = byId.values.filter {
            it.alias?.lowercase() == q || it.announcedName?.lowercase() == q
        }
        return hits.singleOrNull()?.nodeId
    }
}
