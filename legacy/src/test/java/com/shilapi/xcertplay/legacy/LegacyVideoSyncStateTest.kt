package com.shilapi.xcertplay.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVideoSyncStateTest {
    private val delta = byteArrayOf(0, 0, 0, 1, 0x41, 0x11)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x33)

    @Test fun startsWithIdrAndAllowsFollowingPredictedFrames() {
        val gate = LegacyVideoSyncState()
        assertFalse(gate.canQueue(delta))
        assertTrue(gate.canQueue(idr))
        gate.onQueued()
        assertTrue(gate.canQueue(delta))
    }

    @Test fun aNewSurfaceInvalidatesTheOldReferenceChain() {
        val gate = LegacyVideoSyncState()
        gate.onQueued()
        gate.reset()
        assertTrue(gate.awaitingKeyframe)
        assertFalse(gate.canQueue(delta))
        assertTrue(gate.canQueue(idr))
    }

    @Test fun failedInputAttemptMustNotMarkTheIdrQueued() {
        val gate = LegacyVideoSyncState()
        assertTrue(gate.canQueue(idr))
        assertTrue(gate.awaitingKeyframe)
        assertFalse(gate.canQueue(delta))
    }
}
