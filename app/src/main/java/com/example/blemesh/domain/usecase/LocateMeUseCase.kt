package com.example.blemesh.domain.usecase

import com.example.blemesh.domain.repository.LocationRepository
import com.example.blemesh.domain.repository.MeshRepository

/** Place ma position sur la carte sans l'envoyer au réseau. */
class LocateMeUseCase(
    private val repository: MeshRepository,
    private val location: LocationRepository
) {
    suspend operator fun invoke(): Result<Unit> {
        val point = location.currentLocation()
            ?: return Result.failure(IllegalStateException("Position GPS indisponible (active la localisation)"))
        repository.updateMyPosition(point.latitude, point.longitude)
        return Result.success(Unit)
    }
}
