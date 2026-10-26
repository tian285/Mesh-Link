package com.example.blemesh.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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

/** Donner un surnom local à un contact (reste sur ce téléphone uniquement). */
@Composable
fun ContactDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onSave: (alias: String?) -> Unit
) {
    var alias by remember { mutableStateOf(contact.alias ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer le contact") },
        text = {
            Column {
                Text(
                    "ID ${"%04X".format(contact.nodeId)} · pseudo annoncé : ${contact.announcedName ?: "aucun"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    singleLine = true,
                    label = { Text("Mon surnom pour ce contact") },
                    supportingText = { Text("Laisser vide pour utiliser son pseudo annoncé") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(alias.ifBlank { null }); onDismiss() }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
