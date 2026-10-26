package com.example.blemesh

import android.content.Context
import com.example.blemesh.data.ble.BleClient
import com.example.blemesh.data.ble.PhoneMeshBle
import com.example.blemesh.data.local.ContactStore
import com.example.blemesh.data.local.NodeIdStore
import com.example.blemesh.data.local.ProfileStore
import com.example.blemesh.data.location.LocationRepositoryImpl
import com.example.blemesh.data.maps.MapFileStore
import com.example.blemesh.data.repository.MeshRepositoryImpl
import com.example.blemesh.domain.repository.LocationRepository
import com.example.blemesh.domain.repository.MeshRepository
import com.example.blemesh.domain.usecase.ConnectGatewayUseCase
import com.example.blemesh.domain.usecase.DisconnectGatewayUseCase
import com.example.blemesh.domain.usecase.LocateMeUseCase
import com.example.blemesh.domain.usecase.RenameContactUseCase
import com.example.blemesh.domain.usecase.ScanGatewaysUseCase
import com.example.blemesh.domain.usecase.SendTextMessageUseCase
import com.example.blemesh.domain.usecase.SetMyPseudoUseCase
import com.example.blemesh.domain.usecase.SetNodeIdUseCase
import com.example.blemesh.domain.usecase.ShareMyPositionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Injection de dépendances manuelle : assemble toutes les couches. */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bleClient = BleClient(context, appScope)
    private val phoneMeshBle = PhoneMeshBle(context, appScope)

    val meshRepository: MeshRepository = MeshRepositoryImpl(
        ble = bleClient,
        phoneBle = phoneMeshBle,
        nodeIdStore = NodeIdStore(context),
        profileStore = ProfileStore(context),
        contactStore = ContactStore(context),
        scope = appScope
    )
    private val locationRepository: LocationRepository = LocationRepositoryImpl(context)
    val mapFileStore = MapFileStore(context)

    val scanGateways = ScanGatewaysUseCase(meshRepository)
    val connectGateway = ConnectGatewayUseCase(meshRepository)
    val disconnectGateway = DisconnectGatewayUseCase(meshRepository)
    val sendTextMessage = SendTextMessageUseCase(meshRepository)
    val shareMyPosition = ShareMyPositionUseCase(meshRepository, locationRepository)
    val locateMe = LocateMeUseCase(meshRepository, locationRepository)
    val setNodeId = SetNodeIdUseCase(meshRepository)
    val setMyPseudo = SetMyPseudoUseCase(meshRepository)
    val renameContact = RenameContactUseCase(meshRepository)
}
