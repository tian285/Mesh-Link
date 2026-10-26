package com.example.blemesh.ui.connect

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.blemesh.domain.model.ConnectionState
import com.example.blemesh.domain.model.GatewayDevice
import com.example.blemesh.ui.permissions.BlePermissions
import com.example.blemesh.ui.permissions.rememberBlePermissions

@Composable
fun ConnectScreen(
    connection: ConnectionState,
    devices: List<GatewayDevice>,
    isScanning: Boolean,
    phoneLinks: Int,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onEnablePhoneMesh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val permissions = rememberBlePermissions()
    var btEnabled by remember { mutableStateOf(BlePermissions.isBluetoothEnabled(context)) }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { btEnabled = BlePermissions.isBluetoothEnabled(context) }
    LaunchedEffect(permissions.granted, btEnabled) {
        if (permissions.granted && btEnabled) onEnablePhoneMesh()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Connexion à la passerelle ESP32", style = MaterialTheme.typography.titleMedium)
        Text(
            "Téléphones reliés en BLE direct : $phoneLinks. Sans passerelle, les messages passent de " +
                "téléphone en téléphone (TTL 5, doublons ignorés) jusqu'au destinataire ou jusqu'à un ESP32.",
            style = MaterialTheme.typography.bodySmall
        )

        when (connection) {
            is ConnectionState.Connecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Connexion en cours…")
            }
            is ConnectionState.Failed -> Text(connection.reason, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }

        when {
            !permissions.granted -> {
                Text("L'application a besoin de l'autorisation Bluetooth pour trouver ta passerelle.")
                Button(onClick = permissions.request) { Text("Autoriser le Bluetooth") }
            }
            !btEnabled -> {
                Text("Le Bluetooth est désactivé.")
                Button(onClick = {
                    enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }) { Text("Activer le Bluetooth") }
            }
            else -> Button(onClick = if (isScanning) onStopScan else onStartScan) {
                Text(if (isScanning) "Arrêter la recherche" else "Rechercher une passerelle")
            }
        }

        if (isScanning) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (devices.isEmpty() && !isScanning && permissions.granted && btEnabled) {
            Text(
                "Aucune passerelle trouvée. Vérifie que l'ESP32 est allumé, puis relance la recherche. " +
                    "Sur Android 11 ou moins, la localisation doit aussi être activée.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.address }) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${device.address}  ·  ${device.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { onConnect(device.address) },
                            enabled = connection !is ConnectionState.Connecting
                        ) { Text("Connecter") }
                    }
                }
            }
        }
    }
}
