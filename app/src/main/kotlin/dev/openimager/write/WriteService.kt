package dev.openimager.write

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.openimager.MainActivity
import dev.openimager.R
import dev.openimager.appGraph
import dev.openimager.core.image.ImageWriter
import dev.openimager.core.image.WriteCancelledException
import dev.openimager.core.image.WritePhase
import dev.openimager.core.image.WriteProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs the write in the foreground so Android does not stop it when the screen goes off, and keeps
 * a notification with live progress and a cancel action.
 */
class WriteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writer: ImageWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                writer?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> start()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val request = WriteCoordinator.consume()
        if (request == null) {
            stopSelf()
            return
        }

        val notification = buildNotification("Preparing ${request.imageLabel}", null, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()

        scope.launch {
            val repository = applicationContext.appGraph.storageRepository
            try {
                repository.open(request.target).use { device ->
                    val writer = ImageWriter(
                        source = request.source,
                        target = device,
                        options = request.options,
                        onProgress = { progress -> publish(request, progress) },
                    )
                    this@WriteService.writer = writer
                    val result = writer.write()
                    WriteCoordinator.update(
                        WriteState.Finished(result, request.imageLabel, request.targetLabel),
                    )
                    notifyDone(
                        title = "${request.imageLabel} written",
                        text = "${request.targetLabel} is ready. You can remove it now.",
                    )
                }
            } catch (e: WriteCancelledException) {
                WriteCoordinator.update(WriteState.Cancelled)
                notifyDone("Write cancelled", "${request.targetLabel} was left unfinished.")
            } catch (e: Exception) {
                Log.e(TAG, "write failed", e)
                val message = e.message ?: e.javaClass.simpleName
                WriteCoordinator.update(WriteState.Failed(message, request.targetLabel))
                notifyDone("Write failed", message)
            } finally {
                writer = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    @SuppressLint("MissingPermission") // guarded by canPostNotifications()
    private fun publish(request: WriteRequest, progress: WriteProgress) {
        WriteCoordinator.update(
            WriteState.Running(progress, request.imageLabel, request.targetLabel, request.source.compressedSize),
        )
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MILLIS) return
        lastNotificationAt = now

        val percent = progress.fraction?.let { (it * 100).toInt() }
        val title = when (progress.phase) {
            WritePhase.PREPARING -> "Preparing"
            WritePhase.WRITING -> "Writing ${request.imageLabel}"
            WritePhase.VERIFYING -> "Verifying ${request.targetLabel}"
            WritePhase.CUSTOMISING -> "Applying settings"
            WritePhase.FINISHED -> "Finishing"
        }
        val speed = if (progress.bytesPerSecond > 0) {
            " - ${ImageWriter.formatSize(progress.bytesPerSecond)}/s"
        } else {
            ""
        }
        if (canPostNotifications()) {
            notifyManager().notify(NOTIFICATION_ID, buildNotification("$title$speed", percent, ongoing = true))
        }
    }

    private fun buildNotification(text: String, percent: Int?, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, WriteService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true)
                if (ongoing) addAction(0, "Cancel", cancel)
            }
            .build()
    }

    @SuppressLint("MissingPermission") // guarded by canPostNotifications()
    private fun notifyDone(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        if (canPostNotifications()) notifyManager().notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun notifyManager() = NotificationManagerCompat.from(this)

    /**
     * The foreground notification itself is always shown, but updates and the completion notice
     * need the runtime permission on Android 13 and later.
     */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openimager:write").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WriteService"
        private const val NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val NOTIFICATION_INTERVAL_MILLIS = 700L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 4 * 60 * 60 * 1000L
        const val CHANNEL_ID = "writes"
        const val ACTION_START = "dev.openimager.action.START"
        const val ACTION_CANCEL = "dev.openimager.action.CANCEL"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Card writing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress of the image being written" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, WriteService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, WriteService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
