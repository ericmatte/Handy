package computer.handy.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmentsTest {

    @Test
    fun `segments are padded, clamped and merged`() {
        val ranges = listOf(
            SpeechSegments.Range(1_000, 2_000),
            SpeechSegments.Range(2_300, 3_000), // overlaps once padded
            SpeechSegments.Range(9_000, 9_900),
        )
        val merged = SpeechSegments.padAndMerge(ranges, totalSamples = 10_000, padSamples = 200)
        assertEquals(
            listOf(SpeechSegments.Range(800, 3_200), SpeechSegments.Range(8_800, 10_000)),
            merged,
        )
    }

    @Test
    fun `unsorted input is handled`() {
        val merged = SpeechSegments.padAndMerge(
            listOf(SpeechSegments.Range(500, 600), SpeechSegments.Range(0, 100)),
            totalSamples = 1_000,
            padSamples = 0,
        )
        assertEquals(listOf(SpeechSegments.Range(0, 100), SpeechSegments.Range(500, 600)), merged)
    }

    @Test
    fun `extract concatenates the voiced parts`() {
        val pcm = ShortArray(10) { it.toShort() }
        val out = SpeechSegments.extract(pcm, listOf(SpeechSegments.Range(1, 3), SpeechSegments.Range(7, 9)))
        assertArrayEquals(shortArrayOf(1, 2, 7, 8), out)
    }

    @Test
    fun `silence stop waits for speech then for silence`() {
        val stop = SilenceStop(silenceTimeoutMs = 900)
        repeat(10) { assertFalse(stop.accept(false, 30)) } // no speech yet
        repeat(10) { assertFalse(stop.accept(true, 30)) }
        assertTrue(stop.heardSpeech)
        repeat(29) { assertFalse(stop.accept(false, 30)) } // 870 ms
        assertTrue(stop.accept(false, 30)) // 900 ms
    }

    @Test
    fun `silence stop gives up without speech`() {
        val stop = SilenceStop(silenceTimeoutMs = 900, noSpeechTimeoutMs = 300)
        repeat(9) { assertFalse(stop.accept(false, 30)) }
        assertTrue(stop.accept(false, 30))
    }
}
