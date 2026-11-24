package com.example.live.service

import android.app.Activity
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
import com.example.MainActivity
import com.example.R
import com.example.live.LiveSessionController
import com.example.live.permissions.PermissionKind

class LiveAssistantService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val controller = LiveSessionController.getInstance(applicationContext)

        when (intent?.action) {
            ACTION_STOP_ALL -> {
                controller.endLiveSession()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_SESSION -> {
                controller.togglePauseSession()
            }
            ACTION_ATTACH_MEDIA_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_MP_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_MP_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_MP_DATA)
                }
                // Enter foreground with MEDIA_PROJECTION type synchronously BEFORE getMediaProjection()
                startTypedForeground(includeMic = hasMicPermission(), includeProjection = true)
                if (data != null) {
                    controller.attachAuthorizedScreenProjection(resultCode, data)
                } else {
                    controller.reportScreenConsentCancelled("Screen capture authorization data was missing.")
                }
                updateForegroundNotification()
                return START_STICKY
            }
            ACTION_START_WAKE_WORD -> {
                startTypedForeground(
                    includeMic = hasMicPermission(),
                    includeProjection = controller.screenCaptureManager.isAuthorized.value
                )
                controller.startWakeWordInForegroundService()
                updateForegroundNotification()
                return START_STICKY
            }
            else -> {
                startTypedForeground(
                    includeMic = hasMicPermission(),
                    includeProjection = controller.screenCaptureManager.isAuthorized.value
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun hasMicPermission(): Boolean {
        return LiveSessionController.getInstance(applicationContext)
            .permissionManager
            .isGranted(PermissionKind.MICROPHONE)
    }

    private fun startTypedForeground(includeMic: Boolean, includeProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var typeMask = 0
            if (includeMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                typeMask = typeMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (includeProjection) {
                typeMask = typeMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (typeMask != 0) {
                startForeground(NOTIFICATION_ID, notification, typeMask)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val controller = LiveSessionController.getInstance(applicationContext)
        val uiState = controller.uiState.value

        val indicators = buildList {
            if (uiState.isMicListening) add("Mic Active")
            if (uiState.wakeWord.isListening) add("Hey ALLORA Active")
            if (uiState.screenShare.isAuthorized && !uiState.screenShare.isPaused) {
                add("Screen Sharing Active")
            } else if (uiState.screenShare.isAuthorized && uiState.screenShare.isPaused) {
                add("Screen Sharing Paused")
            }
            if (uiState.isFloatingOverlayActive) add("Floating Orb")
        }
        val bodyText = if (indicators.isEmpty()) {
            "ALLORA LIVE session ready"
        } else {
            indicators.joinToString(" • ")
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ALLORA_LIVE, true)
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, LiveAssistantService::class.java).apply {
            action = ACTION_PAUSE_SESSION
        }
        val pausePending = PendingIntent.getService(
            this,
            101,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LiveAssistantService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.live_service_notif_title))
            .setContentText(bodyText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.live_service_pause_action), pausePending)
            .addAction(0, getString(R.string.live_service_stop_action), stopPending)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.live_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.live_service_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "allora_live_channel"
        private const val NOTIFICATION_ID = 4401

        const val EXTRA_OPEN_ALLORA_LIVE = "extra_open_allora_live"
        const val EXTRA_OPEN_AI_CHAT = "extra_open_ai_chat"

        private const val ACTION_START_SESSION = "com.example.live.ACTION_START_SESSION"
        private const val ACTION_START_WAKE_WORD = "com.example.live.ACTION_START_WAKE_WORD"
        private const val ACTION_ATTACH_MEDIA_PROJECTION = "com.example.live.ACTION_ATTACH_MEDIA_PROJECTION"
        private const val ACTION_PAUSE_SESSION = "com.example.live.ACTION_PAUSE_SESSION"
        private const val ACTION_STOP_ALL = "com.example.live.ACTION_STOP_ALL"

        private const val EXTRA_MP_RESULT_CODE = "extra_mp_result_code"
        private const val EXTRA_MP_DATA = "extra_mp_data"

        fun startForegroundSession(context: Context) {
            val intent = Intent(context, LiveAssistantService::class.java).apply {
                action = ACTION_START_SESSION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startWakeWordService(context: Context) {
            val intent = Intent(context, LiveAssistantService::class.java).apply {
                action = ACTION_START_WAKE_WORD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun attachMediaProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, LiveAssistantService::class.java).apply {
                action = ACTION_ATTACH_MEDIA_PROJECTION
                putExtra(EXTRA_MP_RESULT_CODE, resultCode)
                putExtra(EXTRA_MP_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            runCatching {
                context.stopService(Intent(context, LiveAssistantService::class.java))
            }
        }
    }
}
