package com.weekd.miracastreceiver.web

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeStateTest {

    @Test
    fun playbackSnapshotUpdatesAtomically() {
        RuntimeState.resetPlayback()
        RuntimeState.updatePlayback {
            it.copy(
                state = "PLAYING",
                title = "Channel 1",
                uri = "https://example.com/live.m3u8",
                positionMs = 1234L,
                durationMs = 5678L,
                source = "WEB_URL",
                retryAttempt = 2
            )
        }

        val snapshot = RuntimeState.playbackSnapshot()
        assertEquals("PLAYING", snapshot.state)
        assertEquals("Channel 1", snapshot.title)
        assertEquals("https://example.com/live.m3u8", snapshot.uri)
        assertEquals(1234L, snapshot.positionMs)
        assertEquals(5678L, snapshot.durationMs)
        assertEquals("WEB_URL", snapshot.source)
        assertEquals(2, snapshot.retryAttempt)
    }
}
