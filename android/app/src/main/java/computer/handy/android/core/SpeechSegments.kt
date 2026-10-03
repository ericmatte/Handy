package computer.handy.android.core

/**
 * Keeps only the voiced parts of a recording, like desktop Handy's VAD filtering: speech
 * segments are padded (VAD onsets are a little late and would clip first syllables), merged
 * when they overlap, and concatenated.
 */
object SpeechSegments {

    /** A detected speech range in samples, end exclusive. */
    data class Range(val start: Int, val end: Int)

    fun padAndMerge(segments: List<Range>, totalSamples: Int, padSamples: Int): List<Range> {
        val padded = segments
            .map { Range((it.start - padSamples).coerceAtLeast(0), (it.end + padSamples).coerceAtMost(totalSamples)) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        val merged = mutableListOf<Range>()
        for (r in padded) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end) {
                merged[merged.lastIndex] = Range(last.start, maxOf(last.end, r.end))
            } else {
                merged += r
            }
        }
        return merged
    }

    fun extract(pcm: ShortArray, ranges: List<Range>): ShortArray {
        val out = ShortArray(ranges.sumOf { it.end - it.start })
        var offset = 0
        for (r in ranges) {
            System.arraycopy(pcm, r.start, out, offset, r.end - r.start)
            offset += r.end - r.start
        }
        return out
    }
}
