package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.MeshRepository

class ConnectGatewayUseCase(private val repository: MeshRepository) {
    operator fun invoke(address: String) = repository.connect(address)
}
