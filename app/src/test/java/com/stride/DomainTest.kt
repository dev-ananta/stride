package com.stride

import com.stride.domain.Activity
import com.stride.domain.Analyzer
import com.stride.domain.AutoPause
import com.stride.domain.Fmt
import com.stride.domain.Geo
import com.stride.domain.Goal
import com.stride.domain.GoalMetric
import com.stride.domain.GoalPeriod
import com.stride.domain.GpsFilter
import com.stride.domain.Gpx
import com.stride.domain.Json
import com.stride.domain.Mappers
import com.stride.domain.Progress
import com.stride.domain.Records
import com.stride.domain.RouteFollower
import com.stride.domain.SafetyContact
import com.stride.domain.SafetyText
import com.stride.domain.Segment
import com.stride.domain.SegmentMatcher
import com.stride.domain.Settings
import com.stride.domain.Sport
import com.stride.domain.Summary
import com.stride.domain.TrackCodec
import com.stride.domain.TrackPoint
import com.stride.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DomainTest {
    private val utc: ZoneId = ZoneOffset.UTC

    // 1 m of latitude ≈ 1/111_195 degrees (mean-radius haversine).
    private val degPerM = 1.0 / 111_194.93

    /** A straight run north at [speed] m/s, one fix per second. */
    private fun straight(meters: Int, speed: Double = 3.0, t0: Long = 1_700_000_000_000, lat0: Double = 45.0, lon: Double = 7.0, ele: (Double) -> Double = { Double.NaN }): List<TrackPoint> {
        val secs = (meters / speed).toInt()
        return (0..secs).map { s ->
            val d = s * speed
            TrackPoint(t0 + s * 1000L, lat0 + d * degPerM, lon, ele(d))
        }
    }

    @Test fun haversineMatchesKnownDistance() {
        // One degree of latitude on the mean sphere = 111.195 km
        assertEquals(111_195.0, Geo.distance(0.0, 0.0, 1.0, 0.0), 1.0)
        // Paris → London ≈ 343.5 km
        assertEquals(343_500.0, Geo.distance(48.8566, 2.3522, 51.5074, -0.1278), 1_500.0)
    }

    @Test fun summaryDistanceMovingTimeAndPauses() {
        val a = straight(3000, 3.0)
        val t1 = a.last().time
        // 10 minutes later (paused), resume and run 2 km more
        val b = straight(2000, 4.0, t0 = t1 + 600_000, lat0 = a.last().lat + 50 * degPerM)
            .mapIndexed { i, p -> if (i == 0) p.copy(resume = true) else p }
        val track = a + b
        val s = Analyzer.summarize(track, Sport.RUN)
        assertEquals(5000.0, s.distanceM, 5.0) // the 50 m jump across the pause is NOT counted
        assertEquals(1000_000L + 500_000L, s.movingMs) // 1000 s + 500 s
        assertTrue(s.elapsedMs > s.movingMs + 590_000)
        assertEquals(4.0, s.maxSpeed, 0.05)
        assertEquals(5000.0 / 1500.0, s.avgSpeed, 0.01)
    }

    @Test fun standingStillIsNotMovingTime() {
        val run = straight(600, 3.0)
        val t = run.last().time
        val still = (1..120).map { run.last().copy(time = t + it * 1000L) } // 2 min standing at a light
        val s = Analyzer.summarize(run + still, Sport.RUN)
        assertEquals(200_000L, s.movingMs)
        assertEquals(320_000L, s.elapsedMs)
    }

    @Test fun elevationGainIgnoresNoise() {
        // Climb 100 m over 2 km with ±1 m GPS altitude noise
        var flip = 1.0
        val track = straight(2000, 4.0, ele = { d -> flip = -flip; 200 + d * 0.05 + flip })
        val gain = Analyzer.elevationGain(track)
        assertEquals(100.0, gain, 6.0)
        // Pure noise on flat ground adds nothing
        val flat = straight(2000, 4.0, ele = { flip = -flip; 50 + flip * 1.2 })
        assertEquals(0.0, Analyzer.elevationGain(flat), 0.001)
    }

    @Test fun kmSplits() {
        val track = straight(5500, 2.5) // 2.5 m/s → 400 s per km
        val splits = Analyzer.splits(track, 1000.0)
        assertEquals(6, splits.size)
        for (k in 0 until 5) {
            assertEquals(1000.0, splits[k].distanceM, 0.01)
            assertEquals(400_000.0, splits[k].timeMs.toDouble(), 1500.0)
        }
        assertEquals(500.0, splits[5].distanceM, 3.0)
    }

    @Test fun bestEffortsFindFastestStretch() {
        // 2 km easy at 3 m/s, then 1 km hard at 5 m/s, then 2 km easy
        val p1 = straight(2000, 3.0)
        val p2 = straight(1000, 5.0, t0 = p1.last().time + 1000, lat0 = p1.last().lat + 5 * degPerM)
        val p3 = straight(2000, 3.0, t0 = p2.last().time + 1000, lat0 = p2.last().lat + 3 * degPerM)
        val track = p1 + p2 + p3
        val oneK = Analyzer.bestEffort(track, 1000.0)!!
        assertEquals(200_000.0, oneK.toDouble(), 2_500.0)
        val fiveK = Analyzer.bestEffort(track, 5000.0)!!
        assertTrue(fiveK in 1_515_000L..1_540_000L) // ≈ 667 s + 200 s + 667 s
        assertNull(Analyzer.bestEffort(track, 10_000.0))
        val labels = Analyzer.bestEfforts(track, Sport.RUN).map { it.first }
        assertTrue("1 km" in labels && "5 km" in labels && "1 mile" in labels)
        assertTrue("10 km" !in labels)
    }

    @Test fun gpsFilterDropsSpikesAndJitter() {
        val f = GpsFilter(Sport.RUN)
        val base = TrackPoint(0, 45.0, 7.0, accuracy = 5f)
        assertTrue(f.accept(base))
        // 500 m in 1 s — impossible on foot
        assertTrue(!f.accept(TrackPoint(1000, 45.0 + 500 * degPerM, 7.0, accuracy = 5f)))
        // poor accuracy
        assertTrue(!f.accept(TrackPoint(2000, 45.0 + 3 * degPerM, 7.0, accuracy = 80f)))
        // jitter: 0.5 m in 1 s while standing
        assertTrue(!f.accept(TrackPoint(3000, 45.0 + 0.5 * degPerM, 7.0, accuracy = 5f)))
        // real movement
        assertTrue(f.accept(TrackPoint(4000, 45.0 + 9 * degPerM, 7.0, accuracy = 5f)))
    }

    @Test fun autoPausePausesAndResumes() {
        val ap = AutoPause(Sport.RUN, stillMs = 8000)
        val run = straight(60, 3.0)
        run.forEach { ap.update(it) }
        assertTrue(!ap.paused)
        val last = run.last()
        var paused = false
        for (s in 1..12) paused = ap.update(last.copy(time = last.time + s * 1000L))
        assertTrue(paused)
        val again = straight(60, 3.0, t0 = last.time + 13_000, lat0 = last.lat)
        again.forEach { ap.update(it) }
        assertTrue(!ap.paused)
    }

    @Test fun segmentMatchingFindsEffortAndRejectsOtherRoad() {
        val track = straight(4000, 4.0)
        // Segment: from 1000 m to 2000 m along the same line
        val seg = Segment("s1", "Hill", Sport.RUN, track.subList(250, 501).let { Geo.simplify(it, 2.0) }, 0)
        assertEquals(1000.0, seg.distanceM, 5.0)
        val efforts = SegmentMatcher.findEfforts("a1", track, seg)
        assertEquals(1, efforts.size)
        assertEquals(250_000.0, efforts[0].elapsedMs.toDouble(), 3_000.0)

        // Same start/end, but the track bows 300 m east in the middle → not the segment
        val bowed = track.mapIndexed { i, p ->
            val d = i * 4.0
            val east = if (d in 1100.0..1900.0) 300.0 * Math.sin((d - 1100) / 800 * Math.PI) else 0.0
            p.copy(lon = p.lon + east / (111_195.0 * Math.cos(Math.toRadians(45.0))))
        }
        // (the bow also makes the path longer; corridor check is what must reject it)
        assertEquals(0, SegmentMatcher.findEfforts("a2", bowed, seg, corridorM = 40.0).size)

        // Two laps → two efforts
        val back = straight(4000, 4.0, t0 = track.last().time + 1000).reversed().mapIndexed { i, p -> p.copy(time = track.last().time + 1000 + i * 1000L) }
        val lap2 = straight(4000, 4.0, t0 = back.last().time + 1000)
        val twoLaps = SegmentMatcher.findEfforts("a3", track + back + lap2, seg)
        assertEquals(2, twoLaps.size)
    }

    @Test fun simplifyKeepsShape() {
        val line = straight(2000, 1.0) // 2001 collinear points
        val s = Geo.simplify(line, 1.0)
        assertEquals(2, s.size)
        assertEquals(Geo.pathLength(line), Geo.pathLength(s), 0.5)
    }

    @Test fun trimEndsHidesStartAndFinish() {
        val t = straight(3000, 3.0)
        val trimmed = Geo.trimEnds(t, 200.0)
        assertEquals(2600.0, Geo.pathLength(trimmed), 6.0)
        assertTrue(Geo.distance(trimmed.first(), t.first()) >= 199)
        // Too short to trim: unchanged
        val short = straight(300, 3.0)
        assertEquals(short.size, Geo.trimEnds(short, 200.0).size)
    }

    @Test fun routeFollowerOffRouteAndRemaining() {
        val route = straight(2000, 10.0)
        val mid = route[100] // 1000 m along
        val p = RouteFollower.progress(route, mid.lat, mid.lon + 30 / (111_195.0 * Math.cos(Math.toRadians(45.0))))!!
        assertEquals(30.0, p.offRouteM, 1.0)
        assertEquals(1000.0, p.doneM, 35.0)
        assertEquals(1000.0, p.remainingM, 35.0)
    }

    @Test fun goalsAndStreaks() {
        val today = LocalDate.of(2026, 11, 26) // Thursday
        fun act(date: LocalDate, km: Double, sport: Sport = Sport.RUN) = Activity(
            date.toString() + km, "x", sport, date.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            Summary(km * 1000, (km * 300_000).toLong(), (km * 320_000).toLong(), 10.0, 4.0),
        )
        val acts = listOf(
            act(LocalDate.of(2026, 11, 23), 5.0), // Monday this week
            act(LocalDate.of(2026, 11, 25), 8.0),
            act(LocalDate.of(2026, 11, 24), 20.0, Sport.RIDE),
            act(LocalDate.of(2026, 11, 22), 10.0), // Sunday last week
            act(LocalDate.of(2026, 11, 12), 6.0), // two weeks ago
            act(LocalDate.of(2026, 11, 4), 6.0), // three weeks ago
        )
        val g = Goal("g", Sport.RUN, GoalMetric.DISTANCE, GoalPeriod.WEEK, 20_000.0)
        val st = Progress.status(g, acts, today, utc)
        assertEquals(13_000.0, st.current, 0.1)
        assertEquals(0.65, st.fraction, 1e-9)
        assertEquals(4.0 / 7.0, st.expectedFraction, 1e-9)
        assertTrue(st.onTrack)
        assertEquals(3, st.daysLeft)

        val all = Progress.status(Goal("h", null, GoalMetric.COUNT, GoalPeriod.MONTH, 10.0), acts, today, utc)
        assertEquals(6.0, all.current, 0.0)

        assertEquals(4, Progress.weekStreak(acts, today, utc))
        val weekly = Progress.weeklyTotals(acts, today, utc, 4, sport = Sport.RUN)
        assertEquals(listOf(6000.0, 6000.0, 10000.0, 13000.0), weekly.map { it.second })
    }

    @Test fun recordsMergeOnlyImproves() {
        val a1 = Activity("a1", "r", Sport.RUN, 1, Summary.EMPTY)
        val (t1, imp1) = Records.merge(emptyMap(), a1, listOf("5 km" to 1_500_000L, "1 km" to 280_000L))
        assertEquals(setOf("5 km", "1 km"), imp1)
        val a2 = Activity("a2", "r", Sport.RUN, 2, Summary.EMPTY)
        val (t2, imp2) = Records.merge(t1, a2, listOf("5 km" to 1_450_000L, "1 km" to 290_000L))
        assertEquals(setOf("5 km"), imp2)
        assertEquals("a2", t2[Records.recordKey(Sport.RUN, "5 km")]!!.activityId)
        assertEquals("a1", t2[Records.recordKey(Sport.RUN, "1 km")]!!.activityId)
        assertEquals(5000.0, t2[Records.recordKey(Sport.RUN, "5 km")]!!.distanceM, 0.0)
    }

    @Test fun trackCodecRoundTripAndTornWrite() {
        val pts = listOf(
            TrackPoint(1_700_000_000_123, 45.1234567, 7.7654321, 312.5, 4f),
            TrackPoint(1_700_000_001_123, -33.8688197, 151.2092955, Double.NaN, 12f, resume = true),
        )
        val back = TrackCodec.decode(TrackCodec.encode(pts))
        assertEquals(2, back.size)
        assertEquals(pts[0].lat, back[0].lat, 1e-7)
        assertEquals(pts[1].lon, back[1].lon, 1e-7)
        assertEquals(312.5, back[0].ele, 0.01)
        assertTrue(back[1].ele.isNaN())
        assertTrue(back[1].resume && !back[0].resume)
        assertEquals(pts[1].time, back[1].time)

        // Simulate a crash mid-write: the last record is cut short
        val bos = ByteArrayOutputStream()
        TrackCodec.writeHeader(bos); pts.forEach { TrackCodec.writePoint(bos, it) }
        val torn = bos.toByteArray().copyOf(bos.size() - 7)
        assertEquals(1, TrackCodec.decode(torn).size)
    }

    @Test fun jsonRoundTrip() {
        val v = mapOf("a" to 1, "b" to listOf(1.5, "x\"y\n\\z", null, true), "c" to mapOf("ü" to "é ✓"), "n" to -2.5e-3)
        val s = Json.write(v)
        @Suppress("UNCHECKED_CAST")
        val back = Json.parse(s) as Map<String, Any?>
        assertEquals(1.0, back["a"])
        assertEquals(listOf(1.5, "x\"y\n\\z", null, true), back["b"])
        assertEquals(mapOf("ü" to "é ✓"), back["c"])
        assertEquals(-0.0025, back["n"] as Double, 1e-12)
        assertEquals(mapOf("k" to "\u0001"), Json.parse("""{ "k" : "\u0001" }"""))
    }

    @Test fun mappersRoundTrip() {
        val s = Settings(Units.IMPERIAL, 81.5, "Sam", false, true, listOf(SafetyContact("Mom", "+15551234567")), 10, 300)
        assertEquals(s, Mappers.settings(Json.parse(Json.write(Mappers.settings(s))) as Map<*, *>))
        val a = Activity("id1", "Morning Run", Sport.TRAIL_RUN, 123456789, Summary(5012.3, 1_500_000, 1_600_000, 88.0, 4.2), "legs!", false)
        assertEquals(a, Mappers.activity(Json.parse(Json.write(Mappers.activity(a))) as Map<*, *>))
        val g = Goal("g1", null, GoalMetric.TIME, GoalPeriod.YEAR, 3.6e8)
        assertEquals(g, Mappers.goal(Json.parse(Json.write(Mappers.goal(g))) as Map<*, *>))
        // Missing fields → defaults, not crashes
        assertEquals(Settings(), Mappers.settings(emptyMap<String, Any>()))
    }

    @Test fun gpxExportImportRoundTrip() {
        val a = straight(1000, 4.0, ele = { 100 + it * 0.01 })
        val b = straight(500, 4.0, t0 = a.last().time + 60_000, lat0 = a.last().lat).mapIndexed { i, p -> if (i == 0) p.copy(resume = true) else p }
        val xml = Gpx.activity("Run & <fun>", Sport.RUN, a + b, "desc")
        assertTrue(xml.contains("Run &amp; &lt;fun&gt;"))
        assertTrue(xml.contains("<type>running</type>"))
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        val back = Gpx.parse(xml)
        assertEquals("Run & <fun>", back.name)
        assertEquals(a.size + b.size, back.points.size)
        assertTrue(back.hasTimes)
        assertTrue(back.points[a.size].resume)
        assertEquals(a[10].time, back.points[10].time)
        assertEquals(Geo.pathLength(a + b), Geo.pathLength(back.points), 0.5)

        val route = Gpx.parse(Gpx.route("Loop", a))
        assertTrue(!route.hasTimes)
        assertEquals(a.size, route.points.size)

        // A typical third-party file with namespaces, rtept, and self-closing tags
        val foreign = """<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><rte><name><![CDATA[Col du Test]]></name>
            <rtept lat="45.1" lon="6.1"/><rtept lat='45.2' lon='6.2'><ele>1500</ele></rtept></rte></gpx>"""
        val f = Gpx.parse(foreign)
        assertEquals("Col du Test", f.name)
        assertEquals(2, f.points.size)
        assertEquals(1500.0, f.points[1].ele, 0.0)

        val tcx = Gpx.tcx(Sport.RIDE, a, Analyzer.summarize(a, Sport.RIDE), 120)
        assertTrue(tcx.contains("Sport=\"Biking\""))
        assertEquals(a.size, Gpx.parse(tcx).points.size)
    }

    @Test fun formatting() {
        assertEquals("5:00 /km", Fmt.pace(1000.0 / 300, Units.METRIC))
        assertEquals("8:03 /mi", Fmt.pace(1609.344 / 483, Units.IMPERIAL))
        assertEquals("–", Fmt.pace(0.0, Units.METRIC))
        assertEquals("1:02:03", Fmt.duration(3_723_000))
        assertEquals("4:05", Fmt.duration(245_000))
        assertEquals("850 m", Fmt.distance(850.0, Units.METRIC))
        assertEquals("12.35 km", Fmt.distance(12_345.0, Units.METRIC))
        assertEquals("3.11 mi", Fmt.distance(5000.0, Units.IMPERIAL))
        assertEquals("36.0 km/h", Fmt.speed(10.0, Units.METRIC))
        assertEquals("328 ft", Fmt.elevation(100.0, Units.IMPERIAL))
        assertEquals("Morning Run", Fmt.defaultName(Sport.RUN, 7))
        assertEquals("Evening Ride", Fmt.defaultName(Sport.RIDE, 19))
        assertEquals(20_000.0, Fmt.parseGoalTarget("20", GoalMetric.DISTANCE, Units.METRIC)!!, 0.0)
        assertEquals(1609.344 * 2.5, Fmt.parseGoalTarget("2,5", GoalMetric.DISTANCE, Units.IMPERIAL)!!, 1e-9)
        assertNull(Fmt.parseGoalTarget("abc", GoalMetric.COUNT, Units.METRIC))
    }

    @Test fun safetyTexts() {
        assertEquals("+15551234567", SafetyText.cleanPhone(" +1 (555) 123-4567 "))
        assertEquals("5551234567", SafetyText.cleanPhone("555.123.4567"))
        val msg = SafetyText.update("Sam", 45.123456, 7.5, 5230.0, 1_800_000, Units.METRIC, 64)
        assertTrue(msg.startsWith("Sam — 5.23 km in 30m"))
        assertTrue(msg.contains("mlat=45.12346&mlon=7.50000"))
        assertTrue(msg.contains("battery 64%"))
        assertNotNull(SafetyText.started("", Sport.RIDE, null, null, 15).takeIf { it.startsWith("Your contact started a ride") })
        assertTrue(SafetyText.update("Sam", 1.0, 2.0, 0.0, 0, Units.METRIC, null).length < 160 + 40) // ~one SMS
    }

    @Test fun seriesDownsamples() {
        val s = Analyzer.series(straight(5000, 4.0, ele = { it * 0.02 }), buckets = 100)
        assertEquals(100, s.distance.size)
        assertEquals(0.0, s.distance.first(), 0.0)
        assertEquals(5000.0, s.distance.last(), 5.0)
        assertEquals(4.0, s.speed[50], 0.05)
        assertEquals(100.0, s.elevation.last(), 0.5)
    }
}

