package com.example.blemesh.ui.map

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.blemesh.data.maps.PmtilesHeader
import com.example.blemesh.domain.model.DisplayNames
import com.example.blemesh.domain.model.GpsPosition
import com.example.blemesh.ui.permissions.BlePermissions
import com.example.blemesh.ui.util.formatCoordinate
import com.example.blemesh.ui.util.secondsAgo
import kotlinx.coroutines.delay
import kotlin.math.ln

private const val STALE_AFTER_MS = 10 * 60_000L
/** Antananarivo, utilisé tant qu'aucune carte ni position n'est disponible. */
private val DEFAULT_CAMERA = CameraTarget(-18.8792, 47.5079, 12.0, 0)

@Composable
fun MapScreen(
    positions: Map<Int, GpsPosition>,
    names: DisplayNames,
    myNodeId: Int,
    mapStyle: String?,
    mapHeader: PmtilesHeader?,
    onSharePosition: () -> Unit,
    onLocateMe: () -> Unit,
    onImportMap: (Uri) -> Unit,
    onDeleteMap: () -> Unit,
    onWrite: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(5_000); value = System.currentTimeMillis() }
    }
    var focus by remember { mutableStateOf<CameraTarget?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAction?.invoke()
        pendingAction = null
    }
    fun withLocation(action: () -> Unit) {
        if (BlePermissions.hasLocation(context)) action()
        else { pendingAction = action; locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportMap(uri)
    }

    val sorted = positions.values.sortedByDescending { it.timestampMs }
    val markers = sorted.map { p ->
        MapMarker(
            nodeId = p.nodeId,
            latitude = p.latitude,
            longitude = p.longitude,
            label = if (p.nodeId == myNodeId) "Moi" else names.of(p.nodeId),
            isMe = p.nodeId == myNodeId,
            isStale = now - p.timestampMs > STALE_AFTER_MS
        )
    }
    // Caméra au chargement d'un style : zone de la carte importée, sinon dernière position connue
    val styleCamera = remember(mapStyle, mapHeader) {
        mapHeader?.let { h ->
            val span = (h.maxLon - h.minLon).coerceAtLeast(0.01)
            val zoom = (ln(360.0 / span) / ln(2.0) + 1.0).coerceIn(h.minZoom.toDouble(), 16.0)
            CameraTarget(h.centerLat, h.centerLon, zoom, 0)
        } ?: sorted.firstOrNull()?.let { CameraTarget(it.latitude, it.longitude, 14.0, 0) } ?: DEFAULT_CAMERA
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (mapStyle == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                OfflineMap(
                    styleJson = mapStyle,
                    styleCamera = styleCamera,
                    markers = markers,
                    focus = focus,
                    onMarkerClick = { selected = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (mapHeader == null && mapStyle != null) {
                Card(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Aucune carte hors ligne installée : les positions s'affichent sur un fond uni. " +
                                "Importe un fichier .pmtiles préparé sur ordinateur (voir README).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Importer une carte") }
                    }
                }
            }
            selected?.let { id ->
                positions[id]?.let { p ->
                    Card(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(names.of(id), style = MaterialTheme.typography.titleSmall)
                                Text("${formatCoordinate(p.latitude)}, ${formatCoordinate(p.longitude)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(secondsAgo(p.timestampMs, now), style = MaterialTheme.typography.bodySmall)
                            }
                            if (id != myNodeId) TextButton(onClick = { onWrite(id) }) { Text("Écrire") }
                            TextButton(onClick = { selected = null }) { Text("Fermer") }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { withLocation(onLocateMe) }) { Text("Ma position") }
            Button(onClick = { withLocation(onSharePosition) }) { Text("Partager") }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("Carte ▾") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Importer une carte (.pmtiles)") },
                        onClick = { menuOpen = false; importLauncher.launch(arrayOf("*/*")) }
                    )
                    if (mapHeader != null) {
                        DropdownMenuItem(
                            text = { Text("Supprimer la carte installée") },
                            onClick = { menuOpen = false; onDeleteMap() }
                        )
                    }
                }
            }
        }

        if (sorted.isEmpty()) {
            Text(
                "Aucune position pour le moment. « Partager » envoie la tienne à tout le réseau.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        LazyColumn(Modifier.heightIn(max = 180.dp)) {
            items(sorted, key = { it.nodeId }) { p ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable {
                            selected = p.nodeId
                            focus = CameraTarget(p.latitude, p.longitude, 16.0, System.nanoTime())
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(names.of(p.nodeId), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${formatCoordinate(p.latitude)}, ${formatCoordinate(p.longitude)} · ${secondsAgo(p.timestampMs, now)}" +
                            if (mapHeader != null && !mapHeader.contains(p.latitude, p.longitude)) " · hors de la carte" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
