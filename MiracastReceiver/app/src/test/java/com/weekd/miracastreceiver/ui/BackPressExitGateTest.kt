package com.weekd.miracastreceiver.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPressExitGateTest {
    @Test
    fun `second press inside window exits`() {
        val gate = BackPressExitGate(2_000)
        assertFalse(gate.registerPress(1_000))
        assertTrue(gate.registerPress(2_500))
    }

    @Test
    fun `stale second press starts a new window`() {
        val gate = BackPressExitGate(2_000)
        assertFalse(gate.registerPress(1_000))
        assertFalse(gate.registerPress(3_001))
        assertTrue(gate.registerPress(4_500))
    }

    @Test
    fun `clock rollback never exits`() {
        val gate = BackPressExitGate(2_000)
        assertFalse(gate.registerPress(5_000))
        assertFalse(gate.registerPress(4_000))
    }

    @Test
    fun `reset clears an armed exit`() {
        val gate = BackPressExitGate(2_000)
        assertFalse(gate.registerPress(1_000))
        gate.reset()
        assertFalse(gate.registerPress(1_500))
    }
}
