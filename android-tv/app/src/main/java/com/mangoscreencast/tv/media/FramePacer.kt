package com.mangoscreencast.tv.media

import com.mangoscreencast.tv.LatencyMode

/**
 * Decides when each decoded frame is shown. Pure timing, with no codec and
 * no threads, so it can be checked with made-up clocks.
 *
 * Every frame is "now" (the sender gives no useful timestamps), so showing
 * them evenly is up to the receiver. One frame per screen refresh looks
 * smooth; two in one refresh means one is never seen, and a frame scheduled
 * far ahead adds delay. Wi-Fi delivers frames in bursts, so a short burst
 * is spread over the refreshes that follow; past a couple of refreshes a
 * frame goes instead, and the next one takes over the newest refresh
 * already given out from the frame waiting there ([Decision.At.replaces]),
 * so the picture stays current without anything being scheduled behind a
 * frame already handed to the screen.
 *
 * A stream faster than the screen (120 fps on a 60 Hz TV) gets one frame
 * per refresh, the one whose turn is next, and the rest are skipped before
 * they reach the surface: a video surface queues what it's given, so the
 * decoder would otherwise back up behind the screen. The rate is the number
 * of frames in the last second, so a burst or a stall can only move it by a
 * burst's worth; nothing counts as fast before a second has been seen, and
 * a quiet second (nothing changed on the sender's screen) changes nothing.
 */
internal class FramePacer(periodNs: Long = 16_666_667L) {
    sealed class Decision {
        /** Show it as soon as possible. */
        object Now : Decision()

        /**
         * Show it at this time, on the clock [decide] was given. [replaces]:
         * the refresh was already given out, so the screen will show this
         * frame in place of the one waiting for it.
         */
        data class At(val timestampNs: Long, val replaces: Boolean = false) : Decision()

        /** Don't show it: the stream is faster than the screen, and this refresh has its frame. */
        object Skip : Decision()

        /** Don't show it: the screen can't keep up with this burst. */
        object Drop : Decision()
    }

    /** The screen's refresh period. */
    @Volatile
    var periodNs: Long = periodNs
        set(value) {
            if (value > 0) field = value
        }

    @Volatile
    var mode: LatencyMode = LatencyMode.BALANCED

    @Volatile
    private var resetPending = false

    // The schedule. Only the thread calling decide() touches these.
    private var lastTargetNs = 0L
    private var hasTarget = false

    /** The latest time any frame has been scheduled for. */
    private var maxTargetNs = 0L

    /** When the frames of the last [WINDOW_NS] came, oldest at [head], as a ring. */
    private val arrivals = LongArray(RING)
    private var head = 0
    private var size = 0
    private var firstNs = 0L
    private var hasFirst = false
    private var fast = false

    /**
     * A new surface, display mode or decoder: the schedule starts over on the
     * next frame. The rate measured so far stands: the sender hasn't changed.
     */
    fun reset() {
        resetPending = true
    }

    /** True while frames come faster than the screen refreshes, allowing for jitter. */
    val streamIsFasterThanScreen: Boolean get() = fast

    /** A frame has been decoded at `nowNs`: when to show it. */
    fun decide(nowNs: Long): Decision {
        if (resetPending) {
            resetPending = false
            hasTarget = false
            lastTargetNs = 0
            maxTargetNs = 0
        }
        val period = periodNs
        intervalNs(nowNs, period)?.let { interval ->
            fast = if (fast) interval < period * 4 / 5 else interval < period * 7 / 10
        }

        if (fast) {
            // One per refresh: the frame whose slot is up; the others can't be shown.
            val slot = if (hasTarget) maxOf(nowNs, lastTargetNs + period) else nowNs
            if (slot - nowNs > period / 2) return Decision.Skip
            return schedule(slot)
        }
        if (mode == LatencyMode.FASTEST) {
            schedule(nowNs)
            return Decision.Now
        }
        val hold = if (mode == LatencyMode.SMOOTHEST) period else 0L
        val limit = if (mode == LatencyMode.SMOOTHEST) 3 * period else 2 * period
        // One frame per refresh: a frame that comes in the same refresh as the
        // previous one waits for the next.
        val earliest = if (hasTarget) lastTargetNs + period else nowNs
        val target = maxOf(nowNs + hold, earliest)
        if (target - nowNs > limit) {
            // More frames than the screen can show (a long burst, or 60 fps on
            // a 50 Hz TV): skip this one, and step the schedule back one
            // refresh, so the next frame takes over the newest refresh given
            // out rather than waiting behind it.
            lastTargetNs = maxOf(nowNs, lastTargetNs - period)
            hasTarget = true
            return Decision.Drop
        }
        return schedule(target)
    }

    private fun schedule(targetNs: Long): Decision.At {
        val replaces = hasTarget && targetNs <= maxTargetNs
        lastTargetNs = targetNs
        hasTarget = true
        if (targetNs > maxTargetNs) maxTargetNs = targetNs
        return Decision.At(targetNs, replaces)
    }

    /**
     * Mean time between frames over the last second, counting this one, or
     * null when there's no basis for a judgment yet: before a whole second
     * has been seen, whenever the second holds too few frames to tell a
     * rate from a quiet screen, and until the frames in it reach back most
     * of the second (a stream resuming after a pause is judged on what it
     * does, not on the pause). Bursts only ever add frames, so they can't
     * make a stream look fast.
     */
    private fun intervalNs(nowNs: Long, period: Long): Long? {
        if (!hasFirst) {
            firstNs = nowNs
            hasFirst = true
        }
        while (size > 0 && arrivals[head] <= nowNs - WINDOW_NS) {
            head = (head + 1) % RING
            size--
        }
        if (size == RING) {
            head = (head + 1) % RING
            size--
        }
        arrivals[(head + size) % RING] = nowNs
        size++
        if (nowNs - firstNs < WINDOW_NS) return null
        if (size * period < WINDOW_NS / 2) return null
        if (nowNs - arrivals[head] < WINDOW_NS * 4 / 5) return null
        return WINDOW_NS / size
    }

    private companion object {
        const val WINDOW_NS = 1_000_000_000L
        /** Room for a second of frames at any rate a sender might manage. */
        const val RING = 256
    }
}
