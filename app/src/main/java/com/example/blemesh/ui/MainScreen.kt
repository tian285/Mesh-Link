package com.example.blemesh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blemesh.domain.model.ConnectionState
import com.example.blemesh.domain.model.MeshPacket
import com.example.blemesh.ui.chat.ChatScreen
import com.example.blemesh.ui.components.ProfileDialog
import com.example.blemesh.ui.connect.ConnectScreen
import com.example.blemesh.ui.contacts.ContactsScreen
import com.example.blemesh.ui.map.MapScreen
import com.example.blemesh.ui.permissions.BlePermissions

private val TAB_TITLES = listOf("Chat", "Contacts", "Carte", "Passerelle")
private const val TAB_CHAT = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val myNodeId by viewModel.myNodeId.collectAsStateWithLifecycle()
    val myPseudo by viewModel.myPseudo.collectAsStateWithLifecycle()
    val devices by viewModel.scanResults.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val neighbors by viewModel.neighbors.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val phoneLinks by viewModel.phoneLinks.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val positions by viewModel.positions.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val mapStyle by viewModel.mapStyle.collectAsStateWithLifecycle()
    val mapHeader by viewModel.mapHeader.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showProfile by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chatRecipient by rememberSaveable { mutableIntStateOf(MeshPacket.BROADCAST_ID) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }
    // Autorisations déjà accordées (lancements suivants) : le mesh démarre sans passer par l'onglet Passerelle
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (BlePermissions.hasAll(context) && BlePermissions.isBluetoothEnabled(context)) viewModel.startPhoneMesh()
    }

    val conn = connection
    val writeTo: (Int) -> Unit = { id -> chatRecipient = id; tab = TAB_CHAT }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(myPseudo)
                        Text(
                            "$phoneLinks tél. BLE" +
                                if (conn is ConnectionState.Connected) "  ·  ${conn.deviceName}" else "  ·  sans passerelle",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showProfile = true }) { Text("Profil") }
                    if (conn is ConnectionState.Connected) {
                        TextButton(onClick = viewModel::disconnect) { Text("Déconnecter") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> ChatScreen(
                    messages = messages, names = names, contacts = contacts, neighbors = neighbors,
                    recipient = chatRecipient, onRecipientChange = { chatRecipient = it },
                    onSend = viewModel::sendText
                )
                1 -> ContactsScreen(
                    contacts = contacts, neighbors = neighbors, names = names,
                    myPseudo = myPseudo, myNodeId = myNodeId, phoneLinks = phoneLinks,
                    gatewayId = (conn as? ConnectionState.Connected)?.gatewayId,
                    onEditProfile = { showProfile = true }, onWrite = writeTo,
                    onRename = viewModel::renameContact
                )
                2 -> MapScreen(
                    positions = positions, names = names, myNodeId = myNodeId,
                    mapStyle = mapStyle, mapHeader = mapHeader,
                    onSharePosition = viewModel::sharePosition, onLocateMe = viewModel::locateMe,
                    onImportMap = viewModel::importMap, onDeleteMap = viewModel::deleteMap,
                    onWrite = writeTo
                )
                else -> ConnectScreen(
                    connection = conn, devices = devices, isScanning = isScanning, phoneLinks = phoneLinks,
                    onStartScan = viewModel::startScan, onStopScan = viewModel::stopScan,
                    onConnect = viewModel::connect, onEnablePhoneMesh = viewModel::startPhoneMesh
                )
            }
        }
    }

    if (showProfile) {
        ProfileDialog(
            currentPseudo = myPseudo,
            currentId = myNodeId,
            onDismiss = { showProfile = false },
            onConfirm = viewModel::updateProfile
        )
    }
}
