package computer.handy.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import computer.handy.android.core.EnergyVad
import computer.handy.android.core.SilenceStop
import computer.handy.android.core.SpeechSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MicPermissionException : Exception("RECORD_AUDIO not granted")
class MicUnavailableException(message: String) : Exception(message)

/**
 * One-shot 16 kHz mono PCM16 recorder with energy-based auto-stop: use one instance per
 * dictation (a stop requested before [record] starts is honored immediately).
 * The microphone is opened in [record] and released before it returns, whatever happens.
 */
class AudioRecorder(private val context: Context) {

    data class Recording(
        val pcm: ShortArray,
        val sampleRate: Int,
        val heardSpeech: Boolean,
        /** Speech ranges found by Silero, or null when it was not used. */
        val speech: List<SpeechSegments.Range>?,
    )

    private val stopRequested = AtomicBoolean(false)

    /** Ends the current [record] call after the frame being read. */
    fun requestStop() = stopRequested.set(true)

    /**
     * Records until [requestStop], silence after speech, or the VAD's time limits.
     * @param onReady called once the microphone delivers audio (desktop plays its start chime here)
     * @param onLevel called on the recording thread with a 0..1 loudness per 30 ms frame
     */
    @SuppressLint("MissingPermission") // checked explicitly below
    suspend fun record(
        silenceTimeoutMs: Long,
        vad: SileroVad?,
        onReady: () -> Unit = {},
        onLevel: (Float) -> Unit,
    ): Recording = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw MicPermissionException()
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw MicUnavailableException("Unsupported audio format")
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL)
                    .setEncoding(ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE / 2 * BYTES_PER_SAMPLE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(context)
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicUnavailableException("AudioRecord failed to initialize")
        }

        // The energy VAD always drives the level meter; it also decides when to stop if
        // Silero is unavailable.
        val energy = EnergyVad(SAMPLE_RATE, silenceTimeoutMs)
        val stop = SilenceStop(silenceTimeoutMs)
        val samples = PcmBuffer(SAMPLE_RATE * 10)
        val frame = ShortArray(FRAME_SAMPLES)
        var speech: List<SpeechSegments.Range>? = null
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicUnavailableException("Microphone is busy")
            }
            var silencedChecked = false
            var ready = false
            while (!stopRequested.get()) {
                ensureActive()
                val read = record.read(frame, 0, frame.size)
                if (read < 0) throw MicUnavailableException("AudioRecord.read error $read")
                if (read == 0) continue
                if (!ready) {
                    ready = true
                    onReady()
                }
                samples.append(frame, read)
                energy.accept(frame, read)
                onLevel(energy.level)
                if (vad != null) {
                    vad.accept(frame, read)
                    if (stop.accept(vad.isSpeaking, read * 1000L / SAMPLE_RATE)) break
                } else if (energy.shouldStop()) {
                    break
                }
                if (!silencedChecked && samples.size >= SAMPLE_RATE / 4) {
                    silencedChecked = true
                    // Android feeds silence to apps that may not use the mic in the background.
                    if (record.activeRecordingConfiguration?.isClientSilenced == true) {
                        throw MicUnavailableException("Microphone silenced by the system")
                    }
                }
            }
            speech = vad?.finish()
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            if (speech == null) vad?.release()
        }
        val heard = if (vad != null) !speech.isNullOrEmpty() else energy.heardSpeech
        Recording(samples.toArray(), SAMPLE_RATE, heard, speech)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_SAMPLES = SAMPLE_RATE * 30 / 1000
    }
}

/** Growable ShortArray, avoids boxing a List<Short> for minutes of audio. */
private class PcmBuffer(initialCapacity: Int) {
    private var data = ShortArray(initialCapacity)
    var size = 0
        private set

    fun append(src: ShortArray, length: Int) {
        if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
        System.arraycopy(src, 0, data, size, length)
        size += length
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
