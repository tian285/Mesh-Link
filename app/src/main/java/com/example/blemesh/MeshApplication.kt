package com.example.blemesh

import android.app.Application
import org.maplibre.android.MapLibre

class MeshApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this) // moteur de carte, avant toute MapView (aucune clé ni réseau requis)
        container = AppContainer(this)
    }
}
