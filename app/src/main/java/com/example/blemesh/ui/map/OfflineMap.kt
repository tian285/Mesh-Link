package com.example.blemesh.ui.map

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.blemesh.data.maps.MapStyles
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Un point à dessiner : un membre du réseau (ou moi). */
data class MapMarker(
    val nodeId: Int,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val isMe: Boolean,
    val isStale: Boolean
)

/** Demande de déplacement de caméra; [token] change à chaque demande, même vers le même point. */
data class CameraTarget(val latitude: Double, val longitude: Double, val zoom: Double, val token: Long)

private const val SOURCE_NODES = "mesh-nodes"
private const val LAYER_DOTS = "mesh-nodes-dots"
private const val LAYER_LABELS = "mesh-nodes-labels"

/**
 * Carte MapLibre intégrée à Compose. Fonctionne sans réseau : le style ne référence que des
 * assets embarqués et l'archive PMTiles locale. Les positions sont une source GeoJSON mise à jour
 * à chaque changement (cercle coloré + pseudo).
 */
@Composable
fun OfflineMap(
    styleJson: String,
    styleCamera: CameraTarget?,
    markers: List<MapMarker>,
    focus: CameraTarget?,
    onMarkerClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }
    val holder = remember { MapViewLifecycle(mapView) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val clickHandler by rememberUpdatedState(onMarkerClick)

    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.start()
                Lifecycle.Event.ON_RESUME -> holder.resume()
                Lifecycle.Event.ON_PAUSE -> holder.pause()
                Lifecycle.Event.ON_STOP -> holder.stop()
                Lifecycle.Event.ON_DESTROY -> holder.destroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // rejoue START/RESUME si l'écran est déjà actif
        onDispose {
            lifecycle.removeObserver(observer)
            holder.destroy()
        }
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setMinZoomPreference(2.0)
            m.setMaxZoomPreference(19.0)
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapClickListener { latLng ->
                val screen: PointF = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(screen, LAYER_DOTS).firstOrNull()
                val id = hit?.getNumberProperty("id")?.toInt()
                if (id != null) clickHandler(id)
                id != null
            }
            map = m
        }
        onDispose { }
    }

    // (Re)chargement du style quand la carte importée change
    LaunchedEffect(map, styleJson) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(styleJson)) { loaded ->
            addNodeLayers(loaded)
            style = loaded
        }
        styleCamera?.let { m.cameraPosition = it.toCameraPosition() }
    }

    LaunchedEffect(style, markers) {
        val source = style?.getSourceAs<GeoJsonSource>(SOURCE_NODES) ?: return@LaunchedEffect
        source.setGeoJson(markers.toFeatureCollection())
    }

    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newCameraPosition(f.toCameraPosition()), 600)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun CameraTarget.toCameraPosition(): CameraPosition =
    CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(zoom).build()

private fun addNodeLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE_NODES, FeatureCollection.fromFeatures(emptyList<Feature>())))
    style.addLayer(
        CircleLayer(LAYER_DOTS, SOURCE_NODES).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleOpacity(Expression.get("opacity")),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
    )
    style.addLayer(
        SymbolLayer(LAYER_LABELS, SOURCE_NODES).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf(MapStyles.FONT)),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor("#1B1B1F"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.0f)),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true)
        )
    )
}

private fun List<MapMarker>.toFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.longitude, m.latitude)).apply {
            addNumberProperty("id", m.nodeId)
            addStringProperty("label", m.label)
            addStringProperty("color", if (m.isMe) "#1565C0" else "#D84315")
            addNumberProperty("opacity", if (m.isStale) 0.45 else 1.0)
        }
    })

/** Suit le cycle de vie de MapView sans jamais appeler deux fois la même étape. */
private class MapViewLifecycle(private val mapView: MapView) {
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun start() { if (!started && !destroyed) { mapView.onStart(); started = true } }
    fun resume() { start(); if (!resumed && !destroyed) { mapView.onResume(); resumed = true } }
    fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
    fun stop() { pause(); if (started) { mapView.onStop(); started = false } }
    fun destroy() { stop(); if (!destroyed) { mapView.onDestroy(); destroyed = true } }
}
