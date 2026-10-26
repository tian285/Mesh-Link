package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.MeshRepository

class ScanGatewaysUseCase(private val repository: MeshRepository) {
    fun start() = repository.startScan()
    fun stop() = repository.stopScan()
}
