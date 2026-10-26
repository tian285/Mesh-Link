package com.example.blemesh.domain.model

/** Outils sur les identifiants de nœud (16 bits). 0x0000 et 0xFFFF sont réservés. */
object NodeId {
    const val MIN = 0x0001
    const val MAX = 0xFFFE

    fun isValid(id: Int): Boolean = id in MIN..MAX

    /** Accepte "1A2B" ou "0x1A2B" (insensible à la casse). Retourne null si invalide. */
    fun parseHex(input: String): Int? =
        input.trim().removePrefix("0x").removePrefix("0X")
            .toIntOrNull(16)
            ?.takeIf { it in 0..0xFFFF }

    fun format(id: Int): String = "0x%04X".format(id)
}
