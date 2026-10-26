package com.example.blemesh.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.blemesh.AppContainer
import com.example.blemesh.data.maps.PmtilesHeader
import com.example.blemesh.domain.model.ChatMessage
import com.example.blemesh.domain.model.ConnectionState
import com.example.blemesh.domain.model.Contact
import com.example.blemesh.domain.model.DisplayNames
import com.example.blemesh.domain.model.GatewayDevice
import com.example.blemesh.domain.model.GpsPosition
import com.example.blemesh.domain.model.Neighbor
import com.example.blemesh.domain.model.NodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.meshRepository

    val myNodeId: StateFlow<Int> = repository.myNodeId
    val myPseudo: StateFlow<String> = repository.myPseudo
    val connection: StateFlow<ConnectionState> = repository.connectionState
    val scanResults: StateFlow<List<GatewayDevice>> = repository.scanResults
    val isScanning: StateFlow<Boolean> = repository.isScanning
    val neighbors: StateFlow<List<Neighbor>> = repository.neighbors
    val contacts: StateFlow<Map<Int, Contact>> = repository.contacts
    val phoneLinks: StateFlow<Int> = repository.phoneLinks
    val messages: StateFlow<List<ChatMessage>> = repository.messages
    val positions: StateFlow<Map<Int, GpsPosition>> = repository.positions

    /** Traduction ID → nom affiché, recalculée quand l'annuaire ou mon pseudo change. */
    val names: StateFlow<DisplayNames> =
        combine(repository.contacts, repository.myNodeId, repository.myPseudo) { c, id, pseudo ->
            DisplayNames(c, id, pseudo)
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            DisplayNames(repository.contacts.value, repository.myNodeId.value, repository.myPseudo.value)
        )

    // --- Carte hors ligne -------------------------------------------------------
    private val _mapStyle = MutableStateFlow<String?>(null)
    /** Style MapLibre courant (null pendant le chargement). */
    val mapStyle: StateFlow<String?> = _mapStyle.asStateFlow()

    private val _mapHeader = MutableStateFlow<PmtilesHeader?>(null)
    val mapHeader: StateFlow<PmtilesHeader?> = _mapHeader.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Messages ponctuels à afficher (erreurs, confirmations). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        reloadMap()
    }

    private fun reloadMap() {
        viewModelScope.launch {
            val (header, style) = withContext(Dispatchers.IO) {
                container.mapFileStore.header() to container.mapFileStore.currentStyle()
            }
            _mapHeader.value = header
            _mapStyle.value = style
        }
    }

    fun importMap(uri: Uri) {
        viewModelScope.launch {
            _events.tryEmit("Import de la carte en cours…")
            container.mapFileStore.import(uri)
                .onSuccess { h ->
                    _events.tryEmit(
                        "Carte installée (${if (h.isVector) "vectorielle" else "images"}, zoom ${h.minZoom}–${h.maxZoom})"
                    )
                    reloadMap()
                }
                .onFailure { _events.tryEmit(it.message ?: "Import impossible") }
        }
    }

    fun deleteMap() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.mapFileStore.delete() }
            reloadMap()
        }
    }

    // --- Passerelle et mesh -----------------------------------------------------
    fun startScan() = container.scanGateways.start()
    fun stopScan() = container.scanGateways.stop()
    fun connect(address: String) = container.connectGateway(address)
    fun disconnect() = container.disconnectGateway()
    fun startPhoneMesh() = repository.startPhoneMesh()

    // --- Messages et positions ----------------------------------------------------
    fun sendText(dstId: Int, text: String) {
        viewModelScope.launch {
            container.sendTextMessage(dstId, text).onFailure { _events.tryEmit(it.message ?: "Échec de l'envoi") }
        }
    }

    fun sharePosition() {
        viewModelScope.launch {
            container.shareMyPosition()
                .onSuccess { _events.tryEmit("Position envoyée") }
                .onFailure { _events.tryEmit(it.message ?: "Échec de l'envoi de la position") }
        }
    }

    fun locateMe() {
        viewModelScope.launch {
            container.locateMe().onFailure { _events.tryEmit(it.message ?: "Position indisponible") }
        }
    }

    // --- Profil et contacts -------------------------------------------------------
    /** @return null si tout est enregistré, sinon le message d'erreur à afficher. */
    fun updateProfile(pseudo: String, idHex: String): String? {
        val id = NodeId.parseHex(idHex) ?: return "ID invalide (4 chiffres hexadécimaux)"
        if (!NodeId.isValid(id)) return "ID hors plage (0001 à FFFE)"
        container.setMyPseudo(pseudo).onFailure { return it.message ?: "Pseudo invalide" }
        if (id != myNodeId.value) container.setNodeId(idHex).onFailure { return it.message ?: "ID invalide" }
        return null
    }

    fun renameContact(nodeId: Int, alias: String?) = container.renameContact(nodeId, alias)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}
