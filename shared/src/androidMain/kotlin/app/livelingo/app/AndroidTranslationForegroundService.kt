package app.livelingo.app

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * Explicitly user-started microphone foreground service.
 *
 * The AppController, websocket, and AudioRecord are process-owned by AndroidSessionRegistry.
 * This service provides Android's required foreground execution contract and a Stop action.
 * Never silently restart recording after a process kill, reboot, or package upgrade.
 */
class AndroidTranslationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                try {
                    promote()
                    isActive = true
                    pendingStart?.complete(Unit)
                } catch (failure: Exception) {
                    pendingStart?.completeExceptionally(failure)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                AndroidSessionRegistry.stopFromNotification()
                // The controller drains and saves the final transcript, then stops this service.
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun promote() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LiveLingo 即時翻譯", NotificationManager.IMPORTANCE_LOW).apply {
                description = "背景翻譯時顯示麥克風使用狀態與停止按鈕"
                setShowBadge(false)
            }
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openIntent = launch?.let {
            PendingIntent.getActivity(this, 10, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, AndroidTranslationForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LiveLingo 正在進行即時翻譯")
            .setContentText("持續使用麥克風；可切換 App 或鎖定螢幕")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "停止並儲存", stopIntent)
            .apply { if (openIntent != null) setContentIntent(openIntent) }
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
    }

    override fun onDestroy() {
        val voluntary = requestedStop
        isActive = false
        pendingStart?.let {
            if (!it.isCompleted) it.completeExceptionally(IllegalStateException("背景翻譯服務提前結束。"))
        }
        pendingStart = null
        requestedStop = false
        if (!voluntary) AndroidSessionRegistry.stopFromNotification()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.livelingo.action.START_BACKGROUND_TRANSLATION"
        const val ACTION_STOP = "app.livelingo.action.STOP_BACKGROUND_TRANSLATION"
        private const val CHANNEL_ID = "livelingo_background_translation"
        private const val NOTIFICATION_ID = 9014

        @Volatile var isActive: Boolean = false
            private set
        @Volatile private var requestedStop = false
        @Volatile private var pendingStart: CompletableDeferred<Unit>? = null

        /** Must be called while the Activity is visible (Android 14+ microphone permission). */
        fun requestStart(context: Context): CompletableDeferred<Unit> {
            val waiter = CompletableDeferred<Unit>()
            pendingStart = waiter
            requestedStop = false
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AndroidTranslationForegroundService::class.java).setAction(ACTION_START)
                )
            } catch (failure: Exception) {
                waiter.completeExceptionally(failure)
            }
            return waiter
        }

        fun requestStop(context: Context) {
            requestedStop = true
            pendingStart?.let {
                if (!it.isCompleted) it.completeExceptionally(IllegalStateException("翻譯已停止。"))
            }
            context.stopService(Intent(context, AndroidTranslationForegroundService::class.java))
        }
    }
}
