package com.draxter.jarvissveglia

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract

class App : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private val debounced = Runnable { SyncWork.syncSoon(this, "calendario modificato") }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        SyncWork.ensure(this)
        // Mentre l'app è viva, reagisce subito ai cambiamenti del calendario.
        if (CalendarRepo.hasPermission(this)) registerObserver()
    }

    fun registerObserver() {
        try {
            contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI, true,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handler.removeCallbacks(debounced)
                        handler.postDelayed(debounced, 3000)
                    }
                }
            )
        } catch (_: SecurityException) {
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(
            CHANNEL_ALARM, "Sveglia", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Sveglia che suona"
            setSound(null, null) // il suono lo gestisce il servizio, con volume sveglia
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alarm)

        val reminder = NotificationChannel(
            CHANNEL_REMINDER, "Promemoria eventi", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifica all'inizio di ogni evento del calendario"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(reminder)
    }

    companion object {
        const val CHANNEL_ALARM = "alarm_v1"
        const val CHANNEL_REMINDER = "reminder_v1"
    }
}
