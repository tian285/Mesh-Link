package com.example.blemesh.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blemesh.domain.model.Contact
import com.example.blemesh.domain.model.DisplayNames
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.Neighbor
import com.example.blemesh.domain.model.NodeId
import com.example.blemesh.ui.util.formatId
import com.example.blemesh.ui.util.secondsAgo

/** Choix du destinataire par pseudo (recherche par nom ou par ID). */
@Composable
fun RecipientPickerDialog(
    contacts: Map<Int, Contact>,
    neighbors: List<Neighbor>,
    names: DisplayNames,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val direct = neighbors.filter { !it.viaGateway }.map { it.nodeId }.toSet()
    val q = query.trim().lowercase()
    val list = contacts.values
        .filter { c ->
            q.isEmpty() || names.of(c.nodeId).lowercase().contains(q) ||
                (c.announcedName?.lowercase()?.contains(q) == true) || formatId(c.nodeId).lowercase().contains(q)
        }
        .sortedWith(compareByDescending<Contact> { it.nodeId in direct }.thenByDescending { it.lastSeenMs })
    val typedId = NodeId.parseHex(query)?.takeIf { NodeId.isValid(it) && it !in contacts }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Envoyer à…") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Rechercher un pseudo ou un ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        PickerRow("Tout le monde", "diffusion à tous les nœuds") { onPick(MeshPacket.BROADCAST_ID) }
                    }
                    if (typedId != null) {
                        item { PickerRow("ID ${formatId(typedId)}", "nœud pas encore dans l'annuaire") { onPick(typedId) } }
                    }
                    items(list, key = { it.nodeId }) { c ->
                        val detail = buildString {
                            append("ID ").append(formatId(c.nodeId))
                            if (c.nodeId in direct) append(" · voisin BLE direct")
                            else if (c.lastSeenMs > 0) append(" · vu ").append(secondsAgo(c.lastSeenMs, now))
                        }
                        PickerRow(names.of(c.nodeId), detail) { onPick(c.nodeId) }
                    }
                }
                if (contacts.isEmpty()) {
                    Text(
                        "Annuaire vide : les pseudos apparaissent dès qu'un téléphone est entendu (HELLO).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun PickerRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
