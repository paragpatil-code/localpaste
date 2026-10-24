package com.localpaste

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * Foreground service that owns the HTTP server. It runs only between an explicit start from
 * MainActivity and a stop (button, notification action, or [IdleShutdown]). It is never restarted
 * by the system (START_NOT_STICKY) and has no boot receiver.
 */
class ServerService : Service() {

    companion object {
        const val EXTRA_IP = "ip"
        private const val ACTION_STOP = "com.localpaste.STOP"
        private val PORTS = 8080..8090
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1

        /** URL while the server is running, null otherwise. Read on the main thread. */
        var url: String? = null
            private set

        /** Why the last start attempt failed, shown by MainActivity. */
        var lastError: String? = null
            private set

        /** Called on the main thread whenever [url] or [lastError] changes. */
        var listener: (() -> Unit)? = null
    }

    private var server: PasteServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        IdleShutdown.start(this) // the notification's Stop action can start a fresh process here
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // startForegroundService() requires this promptly, even if we end up failing.
        createChannel()
        val current = url
        goForeground(notification(current ?: "Starting…"))
        if (current != null) return START_NOT_STICKY // already running

        val ip = intent?.getStringExtra(EXTRA_IP)
        val started = ip?.let { startServer() }
        if (started == null) {
            lastError = if (ip == null) "Not connected to Wi-Fi" else "No free port in ${PORTS.first}–${PORTS.last}"
            stopSelf()
            return START_NOT_STICKY
        }

        server = started
        lastError = null
        url = "http://$ip:${started.listeningPort}"
        acquireWifiLock()
        IdleShutdown.touch()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(url!!))
        listener?.invoke()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        url = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        listener?.invoke()
    }

    private fun startServer(): PasteServer? {
        val html = assets.open("index.html").bufferedReader().use { it.readText() }
        for (port in PORTS) {
            val s = PasteServer(port, html, IdleShutdown::touch)
            try {
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                return s
            } catch (_: IOException) {
                // Port busy; try the next one.
            }
        }
        return null
    }

    private fun acquireWifiLock() {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(mode, "LocalPaste:server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Server status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LocalPaste server is on")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }
}
