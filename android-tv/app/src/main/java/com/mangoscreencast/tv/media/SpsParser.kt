package com.mangoscreencast.tv.media

/**
 * Reads the picture size from an H.264 or HEVC sequence parameter set, so
 * the decoder can be configured for the stream it is actually about to get
 * (e.g. after an iPhone rotates).
 */
object SpsParser {
    data class Size(val width: Int, val height: Int)

    /** `nal` is the SPS NAL unit without its start code. Returns null if it can't be parsed. */
    fun parse(nal: ByteArray, h265: Boolean): Size? = try {
        val reader = BitReader(unescape(nal))
        if (h265) parseHevc(reader) else parseAvc(reader)
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** Finds the SPS in a run of parameter sets (with start codes) and parses it. */
    fun parseFromConfig(config: ByteArray, h265: Boolean): Size? {
        val sps = NalUnits.split(config, config.size, h265)
            .firstOrNull { it.type == if (h265) 33 else 7 } ?: return null
        var end = sps.end
        // Trailing zero bytes belong to the next start code.
        while (end > sps.header && config[end - 1].toInt() == 0) end--
        return parse(config.copyOfRange(sps.header, end), h265)
    }

    private fun parseAvc(r: BitReader): Size? {
        r.skip(8) // NAL header
        val profile = r.bits(8)
        r.skip(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.skip(1)
            r.ue() // bit_depth_luma
            r.ue() // bit_depth_chroma
            r.skip(1) // qpprime_y_zero_transform_bypass
            if (r.bit() == 1) { // seq_scaling_matrix_present
                for (i in 0 until if (chromaFormat == 3) 12 else 8) {
                    if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1) // gaps_in_frame_num_allowed
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.skip(1)
        r.skip(1) // direct_8x8_inference
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightMapUnits * 16
        if (r.bit() == 1) { // frame_cropping
            val (unitX, unitY) = when (chromaFormat) {
                0 -> 1 to (2 - frameMbsOnly)
                1 -> 2 to 2 * (2 - frameMbsOnly)
                2 -> 2 to (2 - frameMbsOnly)
                else -> 1 to (2 - frameMbsOnly)
            }
            width -= (r.ue() + r.ue()) * unitX
            height -= (r.ue() + r.ue()) * unitY
        }
        return Size(width, height).takeIf { width > 0 && height > 0 }
    }

    private fun parseHevc(r: BitReader): Size? {
        r.skip(16) // NAL header
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayers = r.bits(3)
        r.skip(1) // temporal_id_nesting
        // profile_tier_level
        r.skip(96) // general profile (88 bits) + level (8)
        val profilePresent = BooleanArray(maxSubLayers)
        val levelPresent = BooleanArray(maxSubLayers)
        for (i in 0 until maxSubLayers) {
            profilePresent[i] = r.bit() == 1
            levelPresent[i] = r.bit() == 1
        }
        if (maxSubLayers > 0) for (i in maxSubLayers until 8) r.skip(2)
        for (i in 0 until maxSubLayers) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chromaFormat = r.ue()
        if (chromaFormat == 3) r.skip(1)
        var width = r.ue()
        var height = r.ue()
        if (r.bit() == 1) { // conformance_window
            val subWidth = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
            val subHeight = if (chromaFormat == 1) 2 else 1
            width -= (r.ue() + r.ue()) * subWidth
            height -= (r.ue() + r.ue()) * subHeight
        }
        return Size(width, height).takeIf { width > 0 && height > 0 }
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Removes emulation-prevention bytes (00 00 03 -> 00 00). */
    private fun unescape(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var n = 0
        var zeros = 0
        for (b in data) {
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out[n++] = b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private class BitReader(private val data: ByteArray) {
        private var position = 0

        fun bit(): Int {
            val value = (data[position shr 3].toInt() shr (7 - (position and 7))) and 1
            position++
            return value
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun skip(count: Int) {
            position += count
            if (position > data.size * 8) throw IndexOutOfBoundsException()
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                if (zeros > 31) throw IndexOutOfBoundsException()
            }
            return ((1L shl zeros) - 1 + bits(zeros)).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
