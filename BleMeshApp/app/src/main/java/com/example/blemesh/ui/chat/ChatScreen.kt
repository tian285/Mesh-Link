package com.example.blemesh.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blemesh.domain.model.ChatMessage
import com.example.blemesh.domain.model.Contact
import com.example.blemesh.domain.model.DeliveryStatus
import com.example.blemesh.domain.model.DisplayNames
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.domain.model.Neighbor
import com.example.blemesh.ui.components.RecipientPickerDialog
import com.example.blemesh.ui.util.formatTime
import com.example.blemesh.ui.util.routeLabel

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    names: DisplayNames,
    contacts: Map<Int, Contact>,
    neighbors: List<Neighbor>,
    recipient: Int,
    onRecipientChange: (Int) -> Unit,
    onSend: (dstId: Int, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val payloadBytes = text.toByteArray(Charsets.UTF_8).size
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { "${it.isFromMe}-${it.srcId}-${it.msgId}" }) { MessageBubble(it, names) }
        }

        OutlinedButton(
            onClick = { picking = true },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)
        ) {
            Text("À : ${names.of(recipient)}  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = text.isNotBlank() && payloadBytes <= MeshPacket.MAX_PAYLOAD,
                onClick = {
                    onSend(recipient, text)
                    text = ""
                }
            ) { Text("Envoyer") }
        }
        Text(
            if (payloadBytes > MeshPacket.MAX_PAYLOAD) "Message trop long : $payloadBytes octets (maximum 230)."
            else "Contenu : $payloadBytes/230 octets. Ouvre l'onglet Passerelle pour démarrer le Bluetooth.",
            style = MaterialTheme.typography.labelSmall,
            color = if (payloadBytes > MeshPacket.MAX_PAYLOAD) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
        )
    }

    if (picking) {
        RecipientPickerDialog(
            contacts = contacts,
            neighbors = neighbors,
            names = names,
            onPick = { onRecipientChange(it); picking = false },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, names: DisplayNames) {
    val header = if (msg.isFromMe) {
        "Moi → ${names.of(msg.dstId)}"
    } else {
        names.of(msg.srcId) + if (msg.isBroadcast) " (à tous)" else ""
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (msg.isFromMe) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("$header · ${formatTime(msg.timestampMs)}", style = MaterialTheme.typography.labelSmall)
                Text(msg.text)
                val info = if (msg.isFromMe) statusLabel(msg) else msg.hops?.let { "$it saut(s)" } ?: ""
                if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun statusLabel(msg: ChatMessage): String {
    val route = routeLabel(msg.route).let { if (it.isEmpty()) "" else " ($it)" }
    return when (msg.status) {
        DeliveryStatus.SENT -> (if (msg.isBroadcast) "envoyé" else "envoyé, en attente d'accusé") + route
        DeliveryStatus.DELIVERED -> "✓ reçu par le destinataire$route"
        DeliveryStatus.NO_ACK -> "aucun accusé de réception$route"
        DeliveryStatus.RECEIVED -> ""
    }
}
