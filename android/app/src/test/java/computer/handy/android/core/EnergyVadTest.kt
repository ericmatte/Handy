package computer.handy.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EnergyVadTest {

    private val rate = 16_000
    private val frame = rate * 30 / 1000

    private fun silence() = ShortArray(frame) { ((it % 7) - 3).toShort() }
    private fun speech(amplitude: Double = 8000.0) =
        ShortArray(frame) { (sin(2 * PI * 220 * it / rate) * amplitude).toInt().toShort() }

    private fun EnergyVad.feed(frames: Int, make: () -> ShortArray) = repeat(frames) { accept(make()) }

    @Test
    fun `stops after the silence timeout once speech was heard`() {
        val vad = EnergyVad(rate, silenceTimeoutMs = 900)
        vad.feed(20, ::silence)
        vad.feed(30) { speech() }
        assertTrue(vad.heardSpeech)
        vad.feed(20, ::silence) // 600 ms
        assertFalse(vad.shouldStop())
        vad.feed(15, ::silence) // 1050 ms
        assertTrue(vad.shouldStop())
    }

    @Test
    fun `speech right from the first frame is detected`() {
        val vad = EnergyVad(rate, silenceTimeoutMs = 900)
        vad.feed(5) { speech() }
        assertTrue(vad.heardSpeech)
    }

    @Test
    fun `gives up when nobody speaks`() {
        val vad = EnergyVad(rate, silenceTimeoutMs = 900, noSpeechTimeoutMs = 3000)
        vad.feed(90, ::silence) // 2.7 s
        assertFalse(vad.shouldStop())
        vad.feed(15, ::silence)
        assertTrue(vad.shouldStop())
        assertFalse(vad.heardSpeech)
    }

    @Test
    fun `short pauses between words do not stop the recording`() {
        val vad = EnergyVad(rate, silenceTimeoutMs = 900)
        repeat(5) {
            vad.feed(20) { speech() }
            vad.feed(10, ::silence) // 300 ms pause
            assertFalse(vad.shouldStop())
        }
    }

    @Test
    fun `level follows loudness`() {
        val vad = EnergyVad(rate, silenceTimeoutMs = 900)
        vad.accept(silence())
        val quiet = vad.level
        vad.accept(speech())
        assertTrue(vad.level > quiet)
    }
}
