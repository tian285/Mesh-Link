package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.MeshRepository

class DisconnectGatewayUseCase(private val repository: MeshRepository) {
    operator fun invoke() = repository.disconnect()
}
