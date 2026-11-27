package com.stride.ui.components

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stride.domain.Geo
import com.stride.domain.TrackPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/** A line to draw on the map. */
data class MapLine(val points: List<TrackPoint>, val color: Color, val widthDp: Float = 5f, val dashed: Boolean = false)

/** A round dot (start, finish, you-are-here, waypoints). */
data class MapDot(val lat: Double, val lon: Double, val color: Color, val sizeDp: Float = 14f)

/**
 * OpenStreetMap map (osmdroid). Draws [lines] and [dots]; fits the view to [fitTo] whenever
 * [fitKey] changes; follows [follow] while it's non-null; calls [onTap] with map coordinates.
 * In dark mode the standard OSM tiles are colour-inverted so the map doesn't glare at night.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    dots: List<MapDot> = emptyList(),
    fitTo: List<TrackPoint>? = null,
    fitKey: Any? = fitTo?.size,
    follow: Pair<Double, Double>? = null,
    initialZoom: Double = 15.0,
    interactive: Boolean = true,
    onTap: ((lat: Double, lon: Double) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { Holder() }
    holder.onTap = onTap

    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            controller.setZoom(initialZoom)
            // Required attribution for OpenStreetMap data.
            overlays.add(CopyrightOverlay(ctx).apply { setTextSize(10) })
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    val cb = holder.onTap ?: return false
                    cb(p.latitude, p.longitude); return true
                }
                override fun longPressHelper(p: GeoPoint) = false
            }))
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); map.onDetach() }
    }

    AndroidView(
        modifier = modifier,
        factory = { map },
        update = { mv ->
            mv.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
            mv.setMultiTouchControls(interactive)
            mv.isClickable = interactive

            // Replace our drawn overlays (keep attribution + tap handler).
            mv.overlays.removeAll(holder.drawn)
            holder.drawn.clear()
            for (l in lines) {
                if (l.points.size < 2) continue
                holder.drawn += Polyline(mv).apply {
                    setPoints(l.points.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.apply {
                        color = l.color.toArgb(); strokeWidth = l.widthDp * density
                        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
                        pathEffect = if (l.dashed) android.graphics.DashPathEffect(floatArrayOf(14 * density, 10 * density), 0f) else null
                    }
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false } // let taps fall through (route drawing)
                }
            }
            for (d in dots) {
                holder.drawn += Marker(mv).apply {
                    position = GeoPoint(d.lat, d.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(d.color.toArgb())
                        setStroke((2.5f * density).toInt(), android.graphics.Color.WHITE)
                        val px = (d.sizeDp * density).toInt(); setSize(px, px)
                    }
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> false }
                }
            }
            mv.overlays.addAll(1, holder.drawn) // above attribution, below the tap layer
            mv.invalidate()

            if (fitTo != null && fitTo.size >= 2 && holder.fittedKey != fitKey) {
                holder.fittedKey = fitKey
                val b = Geo.bounds(fitTo)!!
                val box = BoundingBox(b.maxLat, b.maxLon, b.minLat, b.minLon).increaseByScale(1.25f)
                val doFit = { mv.zoomToBoundingBox(box, false, (24 * density).toInt()) ; Unit }
                if (mv.width > 0 && mv.height > 0) doFit() else mv.addOnFirstLayoutListener { _, _, _, _, _ -> doFit() }
            } else if (fitTo != null && fitTo.size == 1 && holder.fittedKey != fitKey) {
                holder.fittedKey = fitKey
                mv.controller.setCenter(GeoPoint(fitTo[0].lat, fitTo[0].lon))
            }
            if (follow != null) mv.controller.animateTo(GeoPoint(follow.first, follow.second))
        },
    )
}

private class Holder {
    val drawn = ArrayList<Overlay>()
    var fittedKey: Any? = Unit
    var onTap: ((Double, Double) -> Unit)? = null
}
