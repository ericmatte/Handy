package computer.handy.android.audio

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import computer.handy.android.core.SpeechSegments

/**
 * Silero VAD (the model desktop Handy uses), run on each recorded frame. Decides when speech
 * has ended and remembers where the speech is, so silence can be cut before transcription
 * (fewer hallucinations, faster decoding). One instance per recording.
 */
class SileroVad private constructor(private val vad: Vad) {

    private val segments = mutableListOf<SpeechSegments.Range>()

    /** The current window is speech. */
    var isSpeaking: Boolean = false
        private set

    fun accept(frame: ShortArray, length: Int) {
        val samples = FloatArray(length) { frame[it] / 32768f }
        vad.acceptWaveform(samples)
        isSpeaking = vad.isSpeechDetected()
        drain()
    }

    /** Ends the recording: returns the speech ranges (in samples) and frees the model. */
    fun finish(): List<SpeechSegments.Range> {
        vad.flush()
        drain()
        vad.release()
        return segments.toList()
    }

    fun release() = vad.release()

    private fun drain() {
        while (!vad.empty()) {
            val segment = vad.front()
            segments += SpeechSegments.Range(segment.start, segment.start + segment.samples.size)
            vad.pop()
        }
    }

    companion object {
        const val ASSET = "silero_vad.onnx"

        /** Null if the model cannot be loaded; callers fall back to the energy VAD. */
        fun create(assets: AssetManager): SileroVad? = try {
            SileroVad(
                Vad(
                    assetManager = assets,
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = ASSET,
                            threshold = 0.5f,
                            minSilenceDuration = 0.25f,
                            minSpeechDuration = 0.25f,
                            windowSize = 512,
                            maxSpeechDuration = 20f,
                        ),
                        sampleRate = AudioRecorder.SAMPLE_RATE,
                        numThreads = 1,
                    ),
                ),
            )
        } catch (e: Throwable) {
            Log.w("HandyVad", "Silero VAD unavailable, using the energy VAD", e)
            null
        }
    }
}
