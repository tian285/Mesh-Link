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
import com.example.blemesh.domain.model.Pseudo

/** Mon profil : pseudo (visible par tous) et ID technique 16 bits (réglage avancé). */
@Composable
fun ProfileDialog(
    currentPseudo: String,
    currentId: Int,
    onDismiss: () -> Unit,
    onConfirm: (pseudo: String, idHex: String) -> String?
) {
    var pseudo by remember { mutableStateOf(currentPseudo) }
    var idHex by remember { mutableStateOf("%04X".format(currentId)) }
    var error by remember { mutableStateOf<String?>(null) }
    val bytes = pseudo.trim().toByteArray(Charsets.UTF_8).size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mon profil") },
        text = {
            Column {
                Text(
                    "Ton pseudo est envoyé aux autres téléphones (HELLO toutes les 20 s, annonce réseau " +
                        "toutes les 5 min) : ils te voient sous ce nom au lieu d'un code hexadécimal.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pseudo,
                    onValueChange = { pseudo = it; error = null },
                    singleLine = true,
                    isError = bytes > Pseudo.MAX_BYTES,
                    label = { Text("Pseudo") },
                    supportingText = { Text("$bytes/${Pseudo.MAX_BYTES} octets") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = idHex,
                    onValueChange = { idHex = it.uppercase().take(6); error = null },
                    singleLine = true,
                    label = { Text("ID technique (hexadécimal)") },
                    supportingText = { Text("Unique par appareil, 0001 à FFFE. À ne changer qu'en cas de doublon.") }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bytes in 1..Pseudo.MAX_BYTES,
                onClick = { error = onConfirm(pseudo, idHex); if (error == null) onDismiss() }
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
