package com.example.blemesh.ui.util

import com.example.blemesh.domain.model.MeshRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

fun formatId(id: Int): String = "%04X".format(id)

fun secondsAgo(timestampMs: Long, nowMs: Long): String {
    val s = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "il y a $s s"
        s < 3600 -> "il y a ${s / 60} min"
        else -> "il y a ${s / 3600} h"
    }
}

fun routeLabel(route: MeshRoute?): String = when (route) {
    MeshRoute.DIRECT -> "direct BLE"
    MeshRoute.GATEWAY -> "via passerelle LoRa"
    MeshRoute.FLOOD -> "relayé par les téléphones"
    MeshRoute.GATEWAY_AND_FLOOD -> "passerelle + téléphones"
    MeshRoute.NONE, null -> ""
}
