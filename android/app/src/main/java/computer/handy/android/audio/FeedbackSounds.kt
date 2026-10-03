package computer.handy.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import computer.handy.android.R
import computer.handy.android.core.SoundTheme

/** Desktop Handy's start/stop sounds (same files, src-tauri/resources). */
class FeedbackSounds(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids = mapOf(
        SoundTheme.MARIMBA to (pool.load(context, R.raw.marimba_start, 1) to pool.load(context, R.raw.marimba_stop, 1)),
        SoundTheme.POP to (pool.load(context, R.raw.pop_start, 1) to pool.load(context, R.raw.pop_stop, 1)),
    )

    fun playStart(theme: SoundTheme, volume: Float) = play(ids.getValue(theme).first, volume)

    fun playStop(theme: SoundTheme, volume: Float) = play(ids.getValue(theme).second, volume)

    private fun play(id: Int, volume: Float) {
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() = pool.release()
}
