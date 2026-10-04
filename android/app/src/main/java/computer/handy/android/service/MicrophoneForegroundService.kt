package computer.handy.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import computer.handy.android.R
import computer.handy.android.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds a `microphone` foreground-service state for the duration of one dictation.
 *
 * Why: RECORD_AUDIO is a while-in-use permission. Since Android 11, and strictly since 14,
 * an app without a visible activity is fed silence unless it runs a microphone FGS.
 * The recording itself happens in [HandyAccessibilityService] (same process); this service
 * only grants the process the microphone capability and shows the "recording" notification.
 * It is started from the button tap and stopped as soon as the mic is released.
 */
class MicrophoneForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val ok = try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException on restricted states.
            Log.w(TAG, "startForeground(microphone) refused", e)
            stopSelf()
            false
        }
        val waiter = pending
        pending = null
        if (waiter == null) {
            // The caller gave up waiting (timeout): nothing will stop us later, so stop now.
            if (ok) stop()
        } else {
            waiter.complete(ok)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_recording),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_handy)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "HandyMicFgs"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        @Volatile private var instance: MicrophoneForegroundService? = null
        @Volatile private var pending: CompletableDeferred<Boolean>? = null

        /**
         * Starts the service and waits until it is in the foreground.
         * @return false if the system refused; the caller may still try to record directly.
         */
        suspend fun start(context: Context): Boolean {
            if (instance != null) return true
            val deferred = CompletableDeferred<Boolean>()
            pending = deferred
            try {
                context.startForegroundService(Intent(context, MicrophoneForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService refused", e)
                pending = null
                return false
            }
            val ok = withTimeoutOrNull(START_TIMEOUT_MS) { deferred.await() }
            if (ok == null) pending = null
            return ok ?: false
        }

        /** Leaves the foreground immediately; called right after the mic is released. */
        fun stop() {
            val service = instance ?: return
            instance = null
            service.stopForeground(STOP_FOREGROUND_REMOVE)
            service.stopSelf()
        }

        private const val START_TIMEOUT_MS = 1_500L
    }
}
