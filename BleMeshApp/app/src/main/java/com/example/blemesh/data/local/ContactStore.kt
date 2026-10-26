package com.example.blemesh.data.local

import android.content.Context
import com.example.blemesh.domain.model.Contact
import org.json.JSONArray
import org.json.JSONObject

/**
 * Annuaire persistant : ID ↔ pseudo annoncé ↔ alias local.
 * Stocké en JSON dans les SharedPreferences (quelques dizaines de contacts au plus).
 */
class ContactStore(context: Context) {

    private val prefs = context.getSharedPreferences("mesh_contacts", Context.MODE_PRIVATE)

    fun load(): Map<Int, Contact> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).associate { i ->
                val o = array.getJSONObject(i)
                val id = o.getInt("id")
                id to Contact(
                    nodeId = id,
                    announcedName = o.optString("name").ifEmpty { null },
                    alias = o.optString("alias").ifEmpty { null },
                    lastSeenMs = o.optLong("seen", 0L)
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(contacts: Collection<Contact>) {
        val array = JSONArray()
        contacts.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.nodeId)
                put("name", c.announcedName ?: "")
                put("alias", c.alias ?: "")
                put("seen", c.lastSeenMs)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "contacts_v1"
    }
}
