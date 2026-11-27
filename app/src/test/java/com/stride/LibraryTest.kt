package com.stride

import com.stride.data.Library
import com.stride.data.Store
import com.stride.domain.Goal
import com.stride.domain.GoalMetric
import com.stride.domain.GoalPeriod
import com.stride.domain.Records
import com.stride.domain.Settings
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import com.stride.domain.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

class LibraryTest {
    private val degPerM = 1.0 / 111_194.93

    private fun run(meters: Int, speed: Double, t0: Long) = (0..(meters / speed).toInt()).map { s ->
        TrackPoint(t0 + s * 1000L, 45.0 + s * speed * degPerM, 7.0)
    }

    private fun tmp(): File = Files.createTempDirectory("pb").toFile()

    @Test fun saveReloadRecordsSegmentsAndDelete() {
        val dir = tmp()
        val lib = Library(Store(dir)) { ZoneOffset.UTC }
        lib.load()

        val slow = lib.addActivity(run(6000, 3.0, 1_700_000_000_000), Sport.RUN, null)
        assertTrue("5 km" in slow.newPrs)
        assertTrue(slow.activity.name.endsWith("Run"))

        // Segment from 1 km to 2 km of the first run, then a faster second run on the same line
        val seg = lib.addSegment("Straight", Sport.RUN, lib.track(slow.activity.id).subList(333, 667))
        assertEquals(1, lib.state.leaderboard(seg.id).size) // found the effort in the existing run

        val fast = lib.addActivity(run(6000, 4.0, 1_700_100_000_000), Sport.RUN, "Tempo")
        assertTrue("5 km" in fast.newPrs)
        assertEquals(listOf("Straight"), fast.segmentPrs)
        assertEquals(fast.activity.id, lib.state.leaderboard(seg.id).first().activityId)

        // A ride on the same road doesn't touch run records or run segments
        val ride = lib.addActivity(run(6000, 8.0, 1_700_200_000_000), Sport.RIDE, null)
        assertTrue("5 km" in ride.newPrs)
        assertEquals(2, lib.state.leaderboard(seg.id).size)
        assertEquals(fast.activity.id, lib.state.records[Records.recordKey(Sport.RUN, "5 km")]!!.activityId)

        lib.addGoal(Goal("", Sport.RUN, GoalMetric.DISTANCE, GoalPeriod.WEEK, 30_000.0))
        lib.saveSettings(Settings(units = Units.IMPERIAL, athleteName = "Sam"))
        lib.addRoute("Loop", Sport.RUN, run(2000, 5.0, 0))

        // Everything survives a restart
        val again = Library(Store(dir)) { ZoneOffset.UTC }.also { it.load() }
        assertEquals(3, again.state.activities.size)
        assertEquals("Tempo", again.state.activity(fast.activity.id)!!.name)
        assertEquals(1, again.state.segments.size)
        assertEquals(1, again.state.goals.size)
        assertTrue(again.state.goals[0].id.isNotBlank())
        assertEquals("Sam", again.state.settings.athleteName)
        assertEquals(2, again.state.routes[0].points.size) // straight line simplified to 2 points
        assertEquals(6000.0, again.state.activity(slow.activity.id)!!.summary.distanceM, 5.0)

        // Deleting the record holder hands the record back to the slower run
        again.deleteActivity(fast.activity.id)
        assertEquals(slow.activity.id, again.state.records[Records.recordKey(Sport.RUN, "5 km")]!!.activityId)
        assertEquals(1, again.state.leaderboard(seg.id).size)

        // Changing a ride to a run moves its records to the run table
        again.updateActivity(again.state.activity(ride.activity.id)!!.copy(sport = Sport.RUN))
        assertEquals(ride.activity.id, again.state.records[Records.recordKey(Sport.RUN, "5 km")]!!.activityId)
        assertTrue(again.state.records.keys.none { it.startsWith("RIDE|") })
        dir.deleteRecursively()
    }

    @Test fun inProgressRecordingSurvivesCrash() {
        val dir = tmp()
        val store = Store(dir)
        val pts = run(500, 3.0, 1_700_000_000_000)
        store.beginRecording(Sport.HIKE, pts[0].time, "route1").use { w -> pts.take(100).forEach(w::append) }
        // Service restarted: continue appending to the same file
        store.continueRecording().use { w -> pts.drop(100).forEach(w::append) }
        val ip = store.loadInProgress()!!
        assertEquals(Sport.HIKE, ip.sport)
        assertEquals("route1", ip.routeId)
        assertEquals(pts.size, ip.points.size)
        store.clearInProgress()
        assertEquals(null, store.loadInProgress())
        dir.deleteRecursively()
    }

    @Test fun corruptIndexIsBackedUpNotLost() {
        val dir = tmp()
        File(dir, "activities.json").writeText("[{\"id\":")
        val lib = Library(Store(dir)).also { it.load() }
        assertEquals(0, lib.state.activities.size)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("activities.json.corrupt-") })
        dir.deleteRecursively()
    }
}
