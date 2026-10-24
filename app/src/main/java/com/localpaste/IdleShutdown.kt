package com.localpaste

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/**
 * Closes the whole app — server, screen and process — after [TIMEOUT_MS] without activity.
 * Activity is any HTTP request or any touch/key in the app. Armed for as long as the process lives,
 * so it also ends a process left running in the background.
 */
object IdleShutdown {
    /** Close everything after this long without an HTTP request or app interaction. */
    const val TIMEOUT_MS = 30 * 60 * 1000L

    @Volatile private var lastActivity = 0L
    private var context: Context? = null

    /** The open screen, if any, so it can be closed too. Main thread only. */
    var activity: Activity? = null

    private val alarm = AlarmManager.OnAlarmListener { check() }

    /** Idempotent; called on the main thread by whichever component starts first. */
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        touch()
        schedule(lastActivity + TIMEOUT_MS)
    }

    /** Cheap enough to call on every request and touch: just a timestamp write. */
    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    // AlarmManager (not Handler) so the timeout counts wall-clock time even while the CPU sleeps.
    // Exact, because inexact set() may fire 10+ minutes late on Android 12+; the OnAlarmListener
    // variant needs no SCHEDULE_EXACT_ALARM permission. It re-arms itself for the remaining time
    // instead of being reset on every touch.
    private fun schedule(at: Long) {
        context!!.getSystemService(AlarmManager::class.java)
            .setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "LocalPaste:idle", alarm, null)
    }

    private fun check() {
        val deadline = lastActivity + TIMEOUT_MS
        if (SystemClock.elapsedRealtime() < deadline) return schedule(deadline)

        val ctx = context ?: return
        activity?.finishAndRemoveTask()
        ctx.stopService(Intent(ctx, ServerService::class.java))
        // Let the service's onDestroy release the Wi-Fi lock and notification first; the OS
        // reclaims anything left over when the process dies.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }
}
