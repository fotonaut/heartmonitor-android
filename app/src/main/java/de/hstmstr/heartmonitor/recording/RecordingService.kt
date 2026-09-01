package de.hstmstr.heartmonitor.recording

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.hstmstr.heartmonitor.HeartMonitorApp
import de.hstmstr.heartmonitor.MainActivity
import de.hstmstr.heartmonitor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service representing an active CSV recording. It does not own the
 * BLE connection (that lives in the app-scoped [de.hstmstr.heartmonitor.ble.HeartRateBleManager]);
 * its job is to keep the process alive and show an ongoing notification while
 * [HeartRateRecorder] collects samples.
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "de.hstmstr.heartmonitor.action.START_RECORDING"
        private const val ACTION_STOP = "de.hstmstr.heartmonitor.action.STOP_RECORDING"

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    private val recorder: HeartRateRecorder
        get() = (application as HeartMonitorApp).recorder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                recorder.stop()
                stopSelfAndForeground()
                return START_NOT_STICKY
            }

            else -> {
                startForegroundWithNotification(recorder.state.value.sampleCount)
                if (!recorder.state.value.isRecording) recorder.start()
                observeRecorder()
            }
        }
        return START_STICKY
    }

    private fun observeRecorder() {
        if (observeJob != null) return
        observeJob = scope.launch {
            recorder.state.collect { state ->
                if (!state.isRecording) {
                    stopSelfAndForeground()
                } else {
                    notify(buildNotification(state.sampleCount))
                }
            }
        }
    }

    private fun stopSelfAndForeground() {
        observeJob?.cancel()
        observeJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aufzeichnung",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Läuft, während Herzfrequenz-Daten aufgezeichnet werden." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(sampleCount: Int) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(sampleCount),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    private fun notify(notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(sampleCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Herzfrequenz wird aufgezeichnet")
            .setContentText("$sampleCount Werte erfasst")
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stoppen", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
