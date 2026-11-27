package com.stride

import com.stride.domain.Analyzer
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import com.stride.record.RecStatus
import com.stride.record.RecorderCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {
    private val degPerM = 1.0 / 111_194.93
    private var lat = 45.0
    private var t = 1_700_000_000_000L

    private fun move(core: RecorderCore, seconds: Int, speed: Double) = repeat(seconds) {
        t += 1000; lat += speed * degPerM
        core.onFix(TrackPoint(t, lat, 7.0, accuracy = 5f))
    }

    @Test fun recordsPausesAutoPausesAndSplits() {
        val splits = ArrayList<Pair<Int, Long>>()
        val saved = ArrayList<TrackPoint>()
        val core = RecorderCore(onPoint = { saved += it }, onSplit = { i, ms, _ -> splits += i to ms })
        core.prepare(Sport.RUN, null, emptyList(), 1000.0)
        core.onFix(TrackPoint(t, lat, 7.0, accuracy = 6f))
        assertEquals(RecStatus.READY, core.state.status)
        assertEquals(0, saved.size) // nothing recorded before Start

        core.start(t, autoPauseOn = true)
        move(core, 400, 3.0) // 1.2 km
        assertEquals(1200.0, core.state.distanceM, 5.0)
        assertEquals(1, splits.size)
        assertEquals(333_000.0, splits[0].second.toDouble(), 2_000.0)

        // Stand still for 60 s → auto-pause kicks in, timer stops
        val activeBefore = core.state.activeMs
        repeat(60) { t += 1000; core.onFix(TrackPoint(t, lat, 7.0, accuracy = 5f)) }
        assertEquals(RecStatus.AUTO_PAUSED, core.state.status)
        assertTrue(core.state.activeMs - activeBefore < 12_000)

        // Move again → resumes by itself, first point flagged as a resume
        move(core, 30, 3.0)
        assertEquals(RecStatus.RECORDING, core.state.status)
        assertTrue(saved.any { it.resume })

        // Manual pause: walking 200 m while paused doesn't count
        core.pause(t)
        val d = core.state.distanceM
        move(core, 50, 4.0)
        assertEquals(d, core.state.distanceM, 0.001)
        core.resume(t)
        move(core, 300, 3.0) // passes 2 km
        assertEquals(2, splits.size)

        val pts = core.finish(t)
        assertEquals(RecStatus.FINISHED, core.state.status)
        assertEquals(saved.size, pts.size)
        val sum = Analyzer.summarize(pts, Sport.RUN)
        assertEquals(core.state.distanceM, sum.distanceM, 1.0)
        // The 200 m walked while paused is a gap, not distance
        assertTrue(sum.distanceM < 2250)
    }

    @Test fun restoreContinuesTotalsAndSplits() {
        val first = RecorderCore()
        first.prepare(Sport.RIDE, "r", emptyList(), 1000.0)
        first.start(t, false)
        move(first, 200, 8.0) // 1.6 km
        val saved = first.finish(t)

        val splits = ArrayList<Int>()
        val core = RecorderCore(onSplit = { i, _, _ -> splits += i })
        core.restore(Sport.RIDE, saved.first().time, "r", emptyList(), saved, 1000.0, false, t)
        assertEquals(first.state.distanceM, core.state.distanceM, 0.5)
        lat += 300 * degPerM; t += 120_000 // phone was off for 2 min, moved 300 m
        move(core, 60, 8.0)
        // the gap isn't counted, the new 480 m is; next split announced is km 2
        assertEquals(first.state.distanceM + 480, core.state.distanceM, 10.0)
        assertEquals(listOf(2), splits)
    }
}
