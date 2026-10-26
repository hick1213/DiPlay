package com.shilapi.xcertplay.legacy

import java.io.ByteArrayOutputStream

/**
 * Pure Annex-B test clip parser. The mock contains no CarPlay/iPhone/MFi
 * credentials and no network addresses. Each sample is one H.264 access unit.
 */
internal object EmulatorMockH264 {
    data class Clip(val avcDecoderConfig: ByteArray, val samples: List<ByteArray>)
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun parse(data: ByteArray): Clip {
        val units = splitNalUnits(data)
        val sps = units.firstOrNull { (it[0].toInt() and 31) == 7 }
            ?: error("Mock H.264 sample is missing SPS")
        val pps = units.firstOrNull { (it[0].toInt() and 31) == 8 }
            ?: error("Mock H.264 sample is missing PPS")
        require(sps.size in 4..65535 && pps.size in 1..65535)
        // ISO/IEC AVCDecoderConfigurationRecord, consumed by the same parser
        // as real CarPlay SETUP / H.264 initialization.
        val avcc = ByteArrayOutputStream().apply {
            write(byteArrayOf(1, sps[1], sps[2], sps[3],
                0xff.toByte(), 0xe1.toByte()))
            write(sps.size shr 8)
            write(sps.size and 0xff)
            write(sps)
            write(1)
            write(pps.size shr 8)
            write(pps.size and 0xff)
            write(pps)
        }.toByteArray()

        val frames = ArrayList<ByteArray>()
        var pending = ByteArrayOutputStream()
        var hasPicture = false
        fun flush() {
            if (hasPicture && pending.size() > 0) frames.add(pending.toByteArray())
            pending = ByteArrayOutputStream()
            hasPicture = false
        }
        for (nal in units) {
            val kind = nal[0].toInt() and 31
            // AUD (NAL type 9) separates access units in our generated sample.
            if (kind == 9 && hasPicture) flush()
            // Also support streams that only repeat SPS/PPS at each IDR.
            if ((kind == 7 || kind == 8) && hasPicture) flush()
            pending.write(startCode)
            pending.write(nal)
            if (kind in 1..5) hasPicture = true
        }
        flush()
        require(frames.size >= 8) { "Mock H.264 sample does not contain enough picture frames" }
        require(frames.any { containsNalType(it, 5) }) {
            "Mock stream must contain decodable IDR frames"
        }
        return Clip(avcc, frames)
    }

    private fun containsNalType(sample: ByteArray, type: Int): Boolean =
        splitNalUnits(sample).any { (it[0].toInt() and 31) == type }

    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            val prefix = when {
                data[i] != 0.toByte() || data[i + 1] != 0.toByte() -> 0
                data[i + 2] == 1.toByte() -> 3
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix != 0) {
                starts.add((i + prefix) to i)
                i += prefix
            } else {
                i++
            }
        }
        val result = ArrayList<ByteArray>()
        for (index in starts.indices) {
            val start = starts[index].first
            val end = if (index + 1 == starts.size) data.size else starts[index + 1].second
            if (end > start) result.add(data.copyOfRange(start, end))
        }
        require(result.isNotEmpty()) { "Expected Annex-B H.264 NAL units" }
        return result
    }
}
