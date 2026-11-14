package ai.steadyride.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps the app running while it talks to the navigation (Namyang route, route comparison).
 *
 * Requesting a route brings the navigation app to the front. Android 14 then freezes this
 * app's process within seconds, which stalls the request/confirm loop until the user comes
 * back. A short-lived foreground service for the duration of the task prevents that.
 */
class NaviTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "경로 확인", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Steady Ride")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "내비 경로를 확인하고 있어요")
            .setOngoing(true)
            .build()
        runCatching {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { Log.w("SteadyRide", "navi task service", it); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "navi_task"
        private const val ID = 7
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            runCatching {
                context.startForegroundService(Intent(context, NaviTaskService::class.java).putExtra(EXTRA_TEXT, text))
            }.onFailure { Log.w("SteadyRide", "navi task service start", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NaviTaskService::class.java)) }
        }
    }
}
