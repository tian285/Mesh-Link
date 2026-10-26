package com.example.blemesh.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blemesh.domain.model.Contact
import com.example.blemesh.domain.model.DisplayNames
import com.example.blemesh.domain.model.Neighbor
import com.example.blemesh.ui.components.ContactDialog
import com.example.blemesh.ui.util.formatId
import com.example.blemesh.ui.util.secondsAgo
import kotlinx.coroutines.delay

/** Annuaire : tous les nœuds entendus, avec pseudo, surnom local et état de voisinage. */
@Composable
fun ContactsScreen(
    contacts: Map<Int, Contact>,
    neighbors: List<Neighbor>,
    names: DisplayNames,
    myPseudo: String,
    myNodeId: Int,
    phoneLinks: Int,
    gatewayId: Int?,
    onEditProfile: () -> Unit,
    onWrite: (Int) -> Unit,
    onRename: (Int, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }
    var editing by remember { mutableStateOf<Contact?>(null) }
    val byNeighbor = neighbors.associateBy { it.nodeId }
    val sorted = contacts.values.sortedWith(
        compareByDescending<Contact> { it.nodeId in byNeighbor }.thenByDescending { it.lastSeenMs }
    )

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(myPseudo, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Mon ID ${formatId(myNodeId)} · $phoneLinks téléphone(s) relié(s) en BLE",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onEditProfile) { Text("Modifier") }
            }
        }

        if (sorted.isEmpty()) {
            Text(
                "Personne pour le moment. Un contact apparaît dès qu'un de ses paquets est reçu " +
                    "(HELLO toutes les 20 s, annonce de pseudo toutes les 5 min).",
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sorted, key = { it.nodeId }) { c ->
                val n = byNeighbor[c.nodeId]
                val state = when {
                    c.nodeId == gatewayId -> "ma passerelle"
                    n != null && !n.viaGateway -> "voisin BLE direct"
                    n != null -> "entendu via la passerelle"
                    c.lastSeenMs > 0 -> "vu ${secondsAgo(c.lastSeenMs, now)}"
                    else -> "jamais vu depuis le démarrage"
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(names.of(c.nodeId), style = MaterialTheme.typography.bodyLarge)
                            val pseudoInfo = if (c.alias != null && c.announcedName != null) " · pseudo « ${c.announcedName} »" else ""
                            Text("ID ${formatId(c.nodeId)}$pseudoInfo", style = MaterialTheme.typography.bodySmall)
                            Text(state, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { editing = c }) { Text("Renommer") }
                        TextButton(onClick = { onWrite(c.nodeId) }) { Text("Écrire") }
                    }
                }
            }
        }
    }

    editing?.let { c ->
        ContactDialog(contact = c, onDismiss = { editing = null }, onSave = { onRename(c.nodeId, it) })
    }
}
