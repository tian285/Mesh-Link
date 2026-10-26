package com.example.blemesh.data.mesh

/**
 * Cache anti-doublons : mémorise les clés (src_id, msg_id) déjà traitées.
 *
 * - [add] est atomique : « tester puis marquer » se fait sous le même verrou. Deux copies du même
 *   paquet arrivant en même temps par deux liens différents ne peuvent donc pas passer toutes les deux.
 * - Les entrées sont rangées par ordre d'arrivée (LinkedHashMap) : l'expiration et l'éviction ne
 *   regardent que la tête de la liste, coût O(1) amorti par appel (au lieu de parcourir tout le cache).
 */
class SeenCache(
    private val capacity: Int = 4_000,
    private val ttlMs: Long = 12 * 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val entries = LinkedHashMap<Long, Long>(capacity * 2)

    /** @return true si la clé est nouvelle (et la mémorise), false si déjà vue. */
    @Synchronized
    fun add(key: Long): Boolean {
        val now = clock()
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > ttlMs) it.remove() else break
        }
        if (entries.containsKey(key)) return false
        entries[key] = now
        if (entries.size > capacity) {
            val first = entries.keys.iterator()
            first.next()
            first.remove()
        }
        return true
    }

    @Synchronized
    fun contains(key: Long): Boolean = entries.containsKey(key)

    @Synchronized
    fun remove(key: Long) {
        entries.remove(key)
    }

    @get:Synchronized
    val size: Int get() = entries.size
}
