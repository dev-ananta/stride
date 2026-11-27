package com.stride.domain

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GPX 1.1 and TCX export (both are accepted by Strava, Garmin Connect, Komoot, Runkeeper, Apple
 * Health importers, etc.), plus a forgiving GPX/TCX importer for routes and old activities.
 */
object Gpx {
    private val iso = DateTimeFormatter.ISO_INSTANT

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun c(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun e(v: Double) = String.format(Locale.US, "%.1f", v)

    fun gpxType(s: Sport) = when (s) {
        Sport.RUN, Sport.TRAIL_RUN -> "running"
        Sport.WALK -> "walking"
        Sport.HIKE -> "hiking"
        Sport.RIDE, Sport.E_BIKE -> "cycling"
        Sport.MOUNTAIN_BIKE -> "mountain_biking"
        Sport.SWIM -> "swimming"
        Sport.KAYAK -> "kayaking"
        Sport.ROW -> "rowing"
        Sport.SKI -> "alpine_skiing"
        Sport.INLINE_SKATE -> "inline_skating"
        Sport.WHEELCHAIR -> "wheelchair"
        Sport.OTHER -> "workout"
    }

    /** A recorded activity: one <trkseg> per stretch between pauses, with timestamps. */
    fun activity(name: String, sport: Sport, points: List<TrackPoint>, description: String = ""): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Stride" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>").append(esc(name)).append("</name>")
        points.firstOrNull()?.let { append("<time>").append(iso.format(Instant.ofEpochMilli(it.time))).append("</time>") }
        append("</metadata>\n")
        append("  <trk>\n    <name>").append(esc(name)).append("</name>\n")
        if (description.isNotBlank()) append("    <desc>").append(esc(description)).append("</desc>\n")
        append("    <type>").append(gpxType(sport)).append("</type>\n    <trkseg>\n")
        points.forEachIndexed { i, p ->
            if (i > 0 && p.resume) append("    </trkseg>\n    <trkseg>\n")
            append("      <trkpt lat=\"").append(c(p.lat)).append("\" lon=\"").append(c(p.lon)).append("\">")
            if (!p.ele.isNaN()) append("<ele>").append(e(p.ele)).append("</ele>")
            append("<time>").append(iso.format(Instant.ofEpochMilli(p.time))).append("</time></trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    /** A planned route (no timestamps) — what navigation apps and bike computers expect. */
    fun route(name: String, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Stride" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>").append(esc(name)).append("</name></metadata>\n")
        append("  <trk>\n    <name>").append(esc(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append(c(p.lat)).append("\" lon=\"").append(c(p.lon)).append("\">")
            if (!p.ele.isNaN()) append("<ele>").append(e(p.ele)).append("</ele>")
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    /** Garmin TCX — some apps (and older Garmin tools) prefer it because it carries distance per point. */
    fun tcx(sport: Sport, points: List<TrackPoint>, summary: Summary, calories: Int): String = buildString {
        val tcxSport = when {
            sport == Sport.RUN || sport == Sport.TRAIL_RUN || sport == Sport.WALK || sport == Sport.HIKE -> "Running"
            sport == Sport.RIDE || sport == Sport.MOUNTAIN_BIKE || sport == Sport.E_BIKE -> "Biking"
            else -> "Other"
        }
        val start = points.firstOrNull()?.time ?: 0L
        val cum = Geo.cumulative(points)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">""").append('\n')
        append("  <Activities>\n    <Activity Sport=\"").append(tcxSport).append("\">\n")
        append("      <Id>").append(iso.format(Instant.ofEpochMilli(start))).append("</Id>\n")
        append("      <Lap StartTime=\"").append(iso.format(Instant.ofEpochMilli(start))).append("\">\n")
        append("        <TotalTimeSeconds>").append(summary.movingMs / 1000).append("</TotalTimeSeconds>\n")
        append("        <DistanceMeters>").append(e(summary.distanceM)).append("</DistanceMeters>\n")
        append("        <MaximumSpeed>").append(String.format(Locale.US, "%.2f", summary.maxSpeed)).append("</MaximumSpeed>\n")
        append("        <Calories>").append(calories).append("</Calories>\n")
        append("        <Intensity>Active</Intensity>\n        <TriggerMethod>Manual</TriggerMethod>\n        <Track>\n")
        points.forEachIndexed { i, p ->
            append("          <Trackpoint><Time>").append(iso.format(Instant.ofEpochMilli(p.time))).append("</Time>")
            append("<Position><LatitudeDegrees>").append(c(p.lat)).append("</LatitudeDegrees><LongitudeDegrees>")
                .append(c(p.lon)).append("</LongitudeDegrees></Position>")
            if (!p.ele.isNaN()) append("<AltitudeMeters>").append(e(p.ele)).append("</AltitudeMeters>")
            append("<DistanceMeters>").append(e(cum[i])).append("</DistanceMeters></Trackpoint>\n")
        }
        append("        </Track>\n      </Lap>\n      <Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>Stride</Name></Creator>\n")
        append("    </Activity>\n  </Activities>\n</TrainingCenterDatabase>\n")
    }

    data class Imported(val name: String?, val points: List<TrackPoint>, val hasTimes: Boolean)

    private val trkpt = Regex("""<(trkpt|rtept|wpt)\b([^>]*?)(/>|>(.*?)</\1>)""", RegexOption.DOT_MATCHES_ALL)
    private val tcxPt = Regex("""<Trackpoint>(.*?)</Trackpoint>""", RegexOption.DOT_MATCHES_ALL)
    private val latAttr = Regex("""lat\s*=\s*["']([-0-9.eE+]+)["']""")
    private val lonAttr = Regex("""lon\s*=\s*["']([-0-9.eE+]+)["']""")
    private val eleTag = Regex("""<(?:ele|AltitudeMeters)>\s*([-0-9.eE+]+)\s*<""")
    private val timeTag = Regex("""<(?:time|Time)>\s*([^<]+?)\s*<""")
    private val nameTag = Regex("""<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", RegexOption.DOT_MATCHES_ALL)
    private val tcxLat = Regex("""<LatitudeDegrees>\s*([-0-9.eE+]+)""")
    private val tcxLon = Regex("""<LongitudeDegrees>\s*([-0-9.eE+]+)""")
    private val trksegSplit = Regex("""</trkseg>""")

    /**
     * Reads track points, route points, or (if nothing else) waypoints from a GPX file; or Trackpoints from TCX.
     * Regex-based on purpose: tolerant of namespaces/extensions that trip up strict parsers.
     */
    fun parse(xml: String): Imported {
        val name = nameTag.find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape)?.takeIf { it.isNotEmpty() }
        if (xml.contains("<Trackpoint>")) {
            val pts = tcxPt.findAll(xml).mapNotNull { m ->
                val body = m.groupValues[1]
                val lat = tcxLat.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = tcxLon.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                TrackPoint(parseTime(timeTag.find(body)?.groupValues?.get(1)) ?: 0L, lat, lon,
                    eleTag.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN)
            }.toList()
            return Imported(name, pts, pts.any { it.time > 0 })
        }
        val out = ArrayList<TrackPoint>()
        for (kind in listOf("trkpt", "rtept", "wpt")) {
            // Track segments: mark the first point after </trkseg> as a resume so gaps aren't counted.
            val chunks = if (kind == "trkpt") xml.split(trksegSplit) else listOf(xml)
            for (chunk in chunks) {
                var first = true
                for (m in trkpt.findAll(chunk)) {
                    if (m.groupValues[1] != kind) continue
                    val attrs = m.groupValues[2]
                    val body = m.groupValues.getOrNull(4).orEmpty()
                    val lat = latAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                    val lon = lonAttr.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                    out += TrackPoint(
                        parseTime(timeTag.find(body)?.groupValues?.get(1)) ?: 0L, lat, lon,
                        eleTag.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN,
                        resume = first && out.isNotEmpty(),
                    )
                    first = false
                }
            }
            if (out.isNotEmpty()) break
        }
        return Imported(name, out, out.isNotEmpty() && out.all { it.time > 0 })
    }

    private fun parseTime(s: String?): Long? = try {
        if (s == null) null else Instant.parse(s.trim()).toEpochMilli()
    } catch (_: Exception) {
        try { java.time.OffsetDateTime.parse(s!!.trim()).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
