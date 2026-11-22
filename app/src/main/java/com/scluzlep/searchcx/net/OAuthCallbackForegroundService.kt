package com.scluzlep.searchcx.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.scluzlep.searchcx.R

/**
 * Short-lived foreground service holding process priority while Custom Tabs
 * completes ChatGPT/Codex OAuth loopback callback on 127.0.0.1:1455.
 */
class OAuthCallbackForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OAuth 登录回调监听",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_searchcx)
            .setContentTitle("SearchCX OAuth 回调监听")
            .setContentText("完成登录后将自动返回应用")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "oauth_loopback_channel"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_STOP = "com.scluzlep.searchcx.ACTION_STOP_OAUTH_FGS"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, OAuthCallbackForegroundService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, OAuthCallbackForegroundService::class.java))
            }
        }
    }
}
