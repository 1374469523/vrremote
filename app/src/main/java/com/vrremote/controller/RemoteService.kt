package com.vrremote.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the controller link and the injection channel alive in the background.
 *
 * It deliberately does not restart on boot: the remote is only active after the
 * user opens the app once.
 */
class RemoteService : Service() {

    companion object {
        private const val CHANNEL_ID = "remote"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, RemoteService::class.java)
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        RemoteCore.init(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            RemoteCore.connectionState.collect { manager.notify(NOTIFICATION_ID, buildNotification()) }
        }
        scope.launch {
            RemoteCore.mode.collect { manager.notify(NOTIFICATION_ID, buildNotification()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RemoteCore.init(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        RemoteCore.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "手柄连接",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "显示手柄连接状态和当前模式"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val state = if (RemoteCore.isInitialised) RemoteCore.connectionState.value else ConnectionState.Idle
        val mode = if (RemoteCore.isInitialised) RemoteCore.mode.value else null
        val text = buildString {
            append(state.label)
            if (mode != null) {
                append(" · ")
                append(mode.label)
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("手柄遥控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
