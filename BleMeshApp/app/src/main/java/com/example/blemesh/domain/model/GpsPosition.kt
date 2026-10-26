package com.example.blemesh.domain.model

data class GpsPosition(
    val nodeId: Int,
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long
)

data class GeoPoint(val latitude: Double, val longitude: Double)
