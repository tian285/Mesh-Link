package com.example.blemesh.domain.model

/**
 * Règles des pseudos. Le pseudo voyage en UTF-8 dans le payload des paquets HELLO, NAME et GPS,
 * l'en-tête binaire (et donc l'ID 16 bits) reste inchangé pour rester compatible avec l'ESP32.
 */
object Pseudo {
    /** Taille maximale en octets UTF-8 (un caractère accentué compte pour 2 octets). */
    const val MAX_BYTES = 24

    /**
     * Nettoie une saisie ou un pseudo reçu : supprime les caractères de contrôle,
     * réduit les espaces, coupe à [MAX_BYTES] octets sans casser un caractère.
     * Retourne null si le résultat est vide.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = buildString {
            for (ch in raw) if (!ch.isISOControl()) append(ch)
        }.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return truncateUtf8(cleaned, MAX_BYTES).trim().ifEmpty { null }
    }

    /** Tronque à [maxBytes] octets UTF-8 au plus, sur une frontière de caractère (code point). */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + n > maxBytes) break
            out.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** Pseudo par défaut tant que l'utilisateur n'en a pas choisi. */
    fun default(nodeId: Int): String = "Mobile-%04X".format(nodeId)
}
