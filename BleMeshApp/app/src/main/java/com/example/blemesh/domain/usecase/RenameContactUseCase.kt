package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.MeshRepository

/** Donne (ou retire, si vide) un surnom local à un contact. */
class RenameContactUseCase(private val repository: MeshRepository) {
    operator fun invoke(nodeId: Int, alias: String?) = repository.renameContact(nodeId, alias)
}
