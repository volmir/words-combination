package com.example.sostavslova.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActiveTimeCounterTest {
    @Test fun pauseResumeAndRepeatedStopsNeverDoubleCount() {
        var tick = 100L
        val counter = ActiveTimeCounter { tick }
        counter.start()
        tick += 20
        counter.start()
        tick += 30
        assertEquals(50L, counter.stop())
        assertNull(counter.stop())
        tick += 5000
        counter.start()
        tick += 7
        assertEquals(7L, counter.stop())
        assertFalse(counter.running)
    }

    @Test fun checkpointContinuesFromNewStartTick() {
        var tick = 0L
        val counter = ActiveTimeCounter { tick }
        assertNull(counter.flush())
        counter.start()
        tick = 60_000
        assertEquals(60_000L, counter.flush())
        tick += 42
        assertEquals(42L, counter.stop())
    }

    @Test fun processRestartHasNoPersistedStartTick() {
        var tick = 1000L
        ActiveTimeCounter { tick }.start()
        tick = 0 // reboot: only accumulated durations may survive
        val restarted = ActiveTimeCounter { tick }
        assertNull(restarted.stop())
        restarted.start()
        tick = 10
        assertEquals(10L, restarted.stop())
    }
}
