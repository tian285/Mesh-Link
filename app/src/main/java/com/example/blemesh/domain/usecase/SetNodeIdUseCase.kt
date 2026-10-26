package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.model.NodeId
import com.example.blemesh.domain.repository.MeshRepository

class SetNodeIdUseCase(private val repository: MeshRepository) {
    operator fun invoke(hexInput: String): Result<Unit> {
        val id = NodeId.parseHex(hexInput)
            ?: return Result.failure(IllegalArgumentException("ID invalide"))
        return repository.setMyNodeId(id)
    }
}
