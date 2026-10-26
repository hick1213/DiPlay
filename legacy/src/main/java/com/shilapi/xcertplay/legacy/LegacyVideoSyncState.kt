package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport

/** Reset the H.264 reference chain whenever the rendering Surface/decoder is rebuilt. */
internal class LegacyVideoSyncState {
    var awaitingKeyframe = true
        private set

    fun reset() { awaitingKeyframe = true }

    fun canQueue(frame: ByteArray): Boolean =
        !awaitingKeyframe || MediaCodecSupport.isRandomAccess(frame, VideoCodec.H264)

    /** The IDR only counts once MediaCodec accepts the frame, not when it arrives. */
    fun onQueued() { awaitingKeyframe = false }
}
