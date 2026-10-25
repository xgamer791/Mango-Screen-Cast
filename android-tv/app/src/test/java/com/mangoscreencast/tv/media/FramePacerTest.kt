package com.mangoscreencast.tv.media

import com.mangoscreencast.tv.LatencyMode
import com.mangoscreencast.tv.media.FramePacer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    private val period = 16_666_667L
    private val ms = 1_000_000L

    /** Decisions for frames decoded at these times (ms). */
    private fun decide(pacer: FramePacer, timesMs: List<Double>): List<Decision> =
        timesMs.map { pacer.decide((it * ms).toLong()) }

    private fun pacer(mode: LatencyMode = LatencyMode.BALANCED) = FramePacer(period).apply { this.mode = mode }

    private fun targets(decisions: List<Decision>) = decisions.filterIsInstance<Decision.At>().map { it.timestampNs }

    /** Refreshes that get a frame of their own: scheduled, and not taken over by a later frame. */
    private fun shown(decisions: List<Decision>) = decisions.count { it is Decision.At && !it.replaces }

    /** A 60 fps stream that Wi-Fi hands over in bursts of `burst` frames, 1 ms apart. */
    private fun bursty(burst: Int, seconds: Int): List<Double> {
        val out = mutableListOf<Double>()
        var frame = 0
        while (frame < 60 * seconds) {
            val start = (frame / burst + 1) * burst * 16.6667 // the whole burst comes when its last frame is due
            for (i in 0 until burst) out += start + i
            frame += burst
        }
        return out
    }

    @Test
    fun aSteadySixtyIsShownOnePerRefresh() {
        val times = (0 until 60).map { it * 16.6667 }
        val decisions = decide(pacer(), times)
        assertTrue(decisions.all { it is Decision.At && !it.replaces })
        val targets = targets(decisions)
        for (i in 1 until targets.size) {
            // Each shown one refresh after the last, never before it was decoded.
            assertEquals(period.toDouble(), (targets[i] - targets[i - 1]).toDouble(), 20_000.0)
            assertTrue(targets[i] >= (times[i] * ms).toLong())
        }
    }

    @Test
    fun aShortBurstIsSpreadOverTheNextRefreshesWithoutDropping() {
        // Three frames Wi-Fi held back arrive together, then the stream goes on.
        val decisions = decide(pacer(), listOf(0.0, 1.0, 2.0, 66.7))
        assertTrue(decisions.toString(), decisions.all { it is Decision.At && !it.replaces })
        val targets = targets(decisions).map { it / ms.toDouble() }
        assertEquals(listOf(0.0, 16.7, 33.3, 66.7), targets.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun aLongerBurstDropsOneAndTheNextTakesOverTheNewestRefresh() {
        val decisions = decide(pacer(), listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
        // The fourth would be shown 47 ms late: it goes, and the fifth takes over the
        // third's refresh; the sixth can't be shown in time either.
        assertEquals(listOf("At", "At", "At", "Drop", "At+", "Drop"), decisions.map {
            when (it) {
                is Decision.At -> if (it.replaces) "At+" else "At"
                else -> it.javaClass.simpleName
            }
        })
        assertEquals(3, shown(decisions))
        assertEquals(3, decisions.count { it is Decision.Drop } + decisions.count { it is Decision.At && it.replaces })
    }

    @Test
    fun targetsNeverGoBackwards() {
        val times = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0) + (1..40).map { 6.0 + it * 16.6667 }
        val targets = targets(decide(pacer(), times))
        for (i in 1 until targets.size) assertTrue("$i", targets[i] >= targets[i - 1])
    }

    @Test
    fun aResumeAfterAQuietSpellNeedsNoWarmUp() {
        val p = pacer()
        decide(p, (0 until 240).map { it * 8.3333 })
        assertTrue(p.streamIsFasterThanScreen)
        // Two seconds of nothing, then 120 fps again: one per refresh from the first frame.
        val after = decide(p, (0 until 120).map { 4_000.0 + it * 8.3333 })
        assertTrue(p.streamIsFasterThanScreen)
        assertFalse(after.any { it is Decision.Drop })
        assertFalse(after.any { it is Decision.At && it.replaces })
        assertEquals(60, after.count { it is Decision.At })
    }

    @Test
    fun aTakeoverOnlyFollowsADrop() {
        val times = listOf(0.0, 1.0, 2.0, 3.0, 4.0) + (1..60).map { 4.0 + it * 16.6667 }
        val decisions = decide(pacer(), times)
        var dropsSoFar = 0
        var takeovers = 0
        for (d in decisions) {
            if (d is Decision.Drop) dropsSoFar++
            if (d is Decision.At && d.replaces) {
                takeovers++
                assertTrue("a takeover before any drop", dropsSoFar >= takeovers)
            }
        }
        assertEquals(1, dropsSoFar)
        assertEquals(1, takeovers)
    }

    @Test
    fun aSixtyDeliveredInBurstsIsNotAFastStream() {
        for (burst in listOf(6, 8, 12)) {
            val p = pacer()
            val decisions = decide(p, bursty(burst, 3))
            assertFalse("burst of $burst", p.streamIsFasterThanScreen)
            assertFalse("burst of $burst", decisions.any { it === Decision.Skip })
            // Each burst gets at least three refreshes of its own; what can't be shown in time goes.
            val bursts = decisions.size / burst
            assertTrue("burst of $burst: ${shown(decisions)} shown", shown(decisions) >= bursts * 3)
        }
    }

    @Test
    fun aStreamFasterThanTheScreenGetsExactlyOneFramePerRefresh() {
        // 120 fps on a 60 Hz screen, for three seconds.
        val p = pacer()
        val decisions = decide(p, (0 until 360).map { it * 8.3333 })
        assertTrue(p.streamIsFasterThanScreen)
        // It takes a second to be sure of the rate; after that nothing is dropped.
        val settled = decisions.drop(150)
        assertFalse(settled.any { it is Decision.Drop })
        assertTrue(settled.toString(), settled.all { it is Decision.At || it === Decision.Skip })
        assertEquals(settled.size / 2, settled.count { it is Decision.At })
        assertEquals(0, settled.count { it is Decision.At && it.replaces })
        val targets = targets(settled)
        for (i in 1 until targets.size) {
            assertEquals(period.toDouble(), (targets[i] - targets[i - 1]).toDouble(), 20_000.0)
        }
    }

    @Test
    fun aQuietScreenKeepsTheRateAndSixtyIsJudgedAgainOnceThereIsEnoughToGoOn() {
        val p = pacer()
        decide(p, (0 until 240).map { it * 8.3333 })
        assertTrue(p.streamIsFasterThanScreen)
        // Nothing changed on the sender's screen for two seconds: still a fast stream.
        p.decide(4_000 * ms)
        assertTrue(p.streamIsFasterThanScreen)
        // Then 60 fps: every frame shown, and within a second it's no longer fast.
        val after = decide(p, (1 until 60).map { 4_000.0 + it * 16.6667 })
        assertTrue(after.toString(), after.all { it is Decision.At })
        assertFalse(after.any { it is Decision.At && it.replaces })
        assertFalse(p.streamIsFasterThanScreen)
    }

    @Test
    fun resetStartsTheScheduleOverButKeepsTheRate() {
        val p = pacer()
        decide(p, (0 until 240).map { it * 8.3333 })
        assertTrue(p.streamIsFasterThanScreen)
        p.reset()
        // A new decoder: no warm-up, one frame per refresh from the first frame.
        val after = decide(p, (0 until 60).map { 2_000.0 + it * 8.3333 })
        assertTrue(p.streamIsFasterThanScreen)
        assertFalse(after.any { it is Decision.Drop })
        assertEquals(Decision.At(2_000 * ms), after[0])
        assertEquals(30, after.count { it is Decision.At })
    }

    @Test
    fun smoothestHoldsOneRefresh() {
        val decisions = decide(pacer(LatencyMode.SMOOTHEST), listOf(0.0, 16.6667))
        assertEquals(period, (decisions[0] as Decision.At).timestampNs)
        assertEquals((2 * period).toDouble(), (decisions[1] as Decision.At).timestampNs.toDouble(), 20_000.0)
    }

    @Test
    fun fastestNeverWaits() {
        val decisions = decide(pacer(LatencyMode.FASTEST), listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
        assertTrue(decisions.all { it === Decision.Now })
    }

    @Test
    fun sixtyOnAFiftyHertzScreenSkipsWhatItCannotShowAndIsNotAFastStream() {
        val p = FramePacer(20 * ms)
        val decisions = decide(p, (0 until 120).map { it * 16.6667 })
        val dropped = decisions.count { it is Decision.Drop }
        assertTrue("dropped $dropped", dropped in 4..25)
        assertFalse(decisions.zipWithNext().any { (a, b) -> a is Decision.Drop && b is Decision.Drop })
        assertFalse(p.streamIsFasterThanScreen)
        assertFalse(decisions.any { it === Decision.Skip })
    }

    @Test
    fun resetForgetsTheSchedule() {
        val p = pacer()
        decide(p, listOf(0.0, 1.0, 2.0))
        p.reset()
        assertEquals(Decision.At((50 * ms)), p.decide(50 * ms))
    }
}
