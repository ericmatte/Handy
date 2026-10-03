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
    )

    private val stopRequested = AtomicBoolean(false)

    /** Ends the current [record] call after the frame being read. */
    fun requestStop() = stopRequested.set(true)

    /**
     * Records until [requestStop], silence after speech, or the VAD's time limits.
     * @param onLevel called on the recording thread with a 0..1 loudness per 30 ms frame
     */
    @SuppressLint("MissingPermission") // checked explicitly below
    suspend fun record(
        silenceTimeoutMs: Long,
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

        val vad = EnergyVad(SAMPLE_RATE, silenceTimeoutMs)
        val samples = PcmBuffer(SAMPLE_RATE * 10)
        val frame = ShortArray(FRAME_SAMPLES)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicUnavailableException("Microphone is busy")
            }
            var silencedChecked = false
            while (!stopRequested.get() && !vad.shouldStop()) {
                ensureActive()
                val read = record.read(frame, 0, frame.size)
                if (read < 0) throw MicUnavailableException("AudioRecord.read error $read")
                if (read == 0) continue
                samples.append(frame, read)
                vad.accept(frame, read)
                onLevel(vad.level)
                if (!silencedChecked && samples.size >= SAMPLE_RATE / 4) {
                    silencedChecked = true
                    // Android feeds silence to apps that may not use the mic in the background.
                    if (record.activeRecordingConfiguration?.isClientSilenced == true) {
                        throw MicUnavailableException("Microphone silenced by the system")
                    }
                }
            }
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
        Recording(samples.toArray(), SAMPLE_RATE, vad.heardSpeech)
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
