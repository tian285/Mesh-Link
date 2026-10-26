package com.example.blemesh.data.local

import android.content.Context
import com.example.blemesh.domain.model.Pseudo

/** Mémorise le pseudo de ce téléphone. */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)

    /** Pseudo enregistré, ou null s'il n'a jamais été choisi. */
    fun loadPseudo(): String? = Pseudo.sanitize(prefs.getString(KEY_PSEUDO, null))

    fun savePseudo(pseudo: String) {
        prefs.edit().putString(KEY_PSEUDO, pseudo).apply()
    }

    private companion object {
        const val KEY_PSEUDO = "pseudo"
    }
}
