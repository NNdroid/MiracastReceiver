package com.weekd.miracastreceiver.ui

/**
 * Monotonic double-back gate used by the TV home screen.
 *
 * The first press arms the gate. A second press inside [windowMs] exits. A stale press,
 * or a clock value moving backwards, starts a new window instead of exiting accidentally.
 */
class BackPressExitGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {
    private var lastPressMs: Long = NO_PRESS

    init {
        require(windowMs > 0) { "windowMs must be positive" }
    }

    fun registerPress(nowMs: Long): Boolean {
        val previous = lastPressMs
        val elapsed = if (previous == NO_PRESS) Long.MAX_VALUE else nowMs - previous
        val shouldExit = elapsed in 0..windowMs
        lastPressMs = if (shouldExit) NO_PRESS else nowMs
        return shouldExit
    }

    fun reset() {
        lastPressMs = NO_PRESS
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
        private const val NO_PRESS = Long.MIN_VALUE
    }
}
