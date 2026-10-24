package com.mangoscreencast.tv.media

/** Helpers for Annex B (start-code delimited) H.264 / H.265 access units. */
object NalUnits {
    /** A NAL unit inside an access unit: [start] is its start code, [header] its first byte. */
    data class Nal(val start: Int, val header: Int, val end: Int, val type: Int)

    fun type(header: Byte, h265: Boolean): Int =
        if (h265) (header.toInt() shr 1) and 0x3F else header.toInt() and 0x1F

    fun isParameterSet(type: Int, h265: Boolean): Boolean =
        if (h265) type in 32..34 else type == 7 || type == 8

    /** IDR/IRAP pictures: decoding can start here. */
    fun isKeyframe(type: Int, h265: Boolean): Boolean =
        if (h265) type in 16..21 else type == 5

    fun isPicture(type: Int, h265: Boolean): Boolean =
        if (h265) type in 0..31 else type in 1..5

    /** Splits `data[0, length)` into NAL units. */
    fun split(data: ByteArray, length: Int, h265: Boolean): List<Nal> {
        val starts = ArrayList<Pair<Int, Int>>() // start code offset, header offset
        var i = 0
        while (i + 3 <= length) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= length && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexedNotNull { index, (start, header) ->
            if (header >= length) return@mapIndexedNotNull null
            val end = if (index + 1 < starts.size) starts[index + 1].first else length
            Nal(start, header, end, type(data[header], h265))
        }
    }

    /** Summary of an access unit for the decoder. */
    data class Info(
        val keyframe: Boolean,
        /** Bytes of leading parameter sets (VPS/SPS/PPS) at the front, 0 if none. */
        val configLength: Int,
        val hasPicture: Boolean,
    )

    /**
     * Classifies an access unit by reading NAL headers only up to the first
     * picture slice, so a multi-megabyte 4K frame costs a few dozen bytes of
     * scanning instead of all of them.
     */
    fun inspect(data: ByteArray, length: Int, h265: Boolean): Info {
        var configEnd = 0
        var previousWasConfig = false
        var i = 0
        while (i + 3 <= length) {
            if (data[i].toInt() != 0 || data[i + 1].toInt() != 0) {
                i++
                continue
            }
            val header = when {
                data[i + 2].toInt() == 1 -> i + 3
                i + 4 <= length && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                else -> {
                    i++
                    continue
                }
            }
            if (header >= length) break
            // The previous NAL unit ends where this start code begins.
            if (previousWasConfig) configEnd = i
            val type = type(data[header], h265)
            if (isPicture(type, h265)) return Info(isKeyframe(type, h265), configEnd, hasPicture = true)
            previousWasConfig = isParameterSet(type, h265)
            i = header + 1
        }
        if (previousWasConfig) configEnd = length
        return Info(keyframe = false, configLength = configEnd, hasPicture = false)
    }

    /** Parameter-set NAL units (with start codes) of a keyframe, for MediaFormat csd buffers. */
    fun parameterSets(data: ByteArray, length: Int, h265: Boolean): List<ByteArray> =
        split(data, length, h265)
            .filter { isParameterSet(it.type, h265) }
            .map { data.copyOfRange(it.start, it.end) }
}
