package com.example.blemesh.domain.repository

import com.example.blemesh.domain.model.GeoPoint

interface LocationRepository {
    /** Position GPS actuelle, ou null si indisponible (permission, GPS éteint, délai dépassé). */
    suspend fun currentLocation(): GeoPoint?
}
