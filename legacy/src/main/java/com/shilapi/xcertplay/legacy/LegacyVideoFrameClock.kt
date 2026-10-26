package com.shilapi.xcertplay.legacy

/**
 * MediaCodec timestamps from the iPhone's per-frame clock rather than assuming that
 * each packet means precisely 33.333 ms. Skipped source frames keep their real gap.
 * Only the *difference* between NTP timestamps is used (never the NTP epoch).
 */
internal class LegacyVideoFrameClock {
    private var originNs = 0L
    private var basePtsUs = 0L
    private var lastPtsUs = -1L

    fun next(senderNanos: Long): Long {
        if (originNs == 0L && senderNanos > 0L) {
            originNs = senderNanos
            basePtsUs = if (lastPtsUs < 0L) 0L else lastPtsUs + FALLBACK_FRAME_US
        }
        val diff = if (originNs > 0L && senderNanos >= originNs) senderNanos - originNs else -1L
        val proposed = if (diff >= 0 && diff < MAX_ELAPSED_NS) basePtsUs + diff / 1000L
            else lastPtsUs + FALLBACK_FRAME_US
        val next = maxOf(0L, lastPtsUs + 1L, proposed)
        lastPtsUs = next
        return next
    }

    companion object {
        private const val FALLBACK_FRAME_US = 33_333L
        private const val MAX_ELAPSED_NS = 60L * 60L * 1_000_000_000L
    }
}
