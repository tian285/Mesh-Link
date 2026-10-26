package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.LocationRepository
import com.example.blemesh.domain.repository.MeshRepository

class ShareMyPositionUseCase(
    private val repository: MeshRepository,
    private val location: LocationRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        val point = location.currentLocation()
            ?: return Result.failure(IllegalStateException("Position GPS indisponible (active la localisation)"))
        return repository.sendPosition(point.latitude, point.longitude)
    }
}
