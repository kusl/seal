package com.junkfood.seal

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.NotificationUtil.SERVICE_NOTIFICATION_ID

private const val TAG = "DownloadService"

/** This `Service` does nothing */
class DownloadService : Service() {

    override fun onBind(intent: Intent): IBinder {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }
        val notification = NotificationUtil.makeServiceNotification(pendingIntent)
        // startForeground() can throw on API 31+ when the service is brought up while the app is
        // in the background (ForegroundServiceStartNotAllowedException) or when a foreground-start
        // restriction otherwise applies. Because onBind() runs on the main thread, an uncaught
        // exception here would be routed to App's default uncaught-exception handler and crash the
        // whole process. The binding itself is still valid even if we can't promote it to the
        // foreground, so we swallow the failure and keep running as an ordinary bound service.
        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "onBind: unable to promote to foreground service", e)
        }
        return DownloadServiceBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: ")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onUnbind: unable to stop foreground", e)
        }
        stopSelf()
        return super.onUnbind(intent)
    }

    inner class DownloadServiceBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}
