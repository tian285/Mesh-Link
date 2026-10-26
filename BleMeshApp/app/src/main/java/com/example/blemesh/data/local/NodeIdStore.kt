package com.example.blemesh.data.local

import android.content.Context
import com.example.blemesh.domain.model.NodeId
import kotlin.random.Random

/** Mémorise l'identifiant de nœud de ce téléphone (généré au premier lancement). */
class NodeIdStore(context: Context) {

    private val prefs = context.getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)

    fun load(): Int {
        val saved = prefs.getInt(KEY_NODE_ID, 0)
        if (NodeId.isValid(saved)) return saved
        // Plage haute (0x1000..0xFFFE) pour ne pas entrer en collision avec les ESP32 (0x0001, 0x0002...)
        val generated = Random.nextInt(0x1000, 0xFFFF)
        save(generated)
        return generated
    }

    fun save(id: Int) {
        prefs.edit().putInt(KEY_NODE_ID, id).apply()
    }

    private companion object {
        const val KEY_NODE_ID = "node_id"
    }
}
