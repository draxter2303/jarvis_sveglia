package com.draxter.jarvissveglia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Scatta all'orario della sveglia. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)) {
            Prefs.setSnoozeAt(ctx, 0L)
        }
        val svc = Intent(ctx, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtras(intent)
        try {
            ContextCompat.startForegroundService(ctx, svc)
        } catch (e: Exception) {
            // Senza permesso "sveglie esatte" Android 12+ può vietare l'avvio del servizio:
            // in quel caso mostra almeno la sveglia come notifica a schermo intero.
            fallbackNotification(ctx, intent)
        }
        // Programma subito le sveglie successive.
        SyncWork.syncSoon(ctx, "dopo una sveglia")
    }
}

private fun fallbackNotification(ctx: Context, intent: Intent) {
    val title = AlarmService.cleanTitle(intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Sveglia")
    AlarmState.title = title
    AlarmState.description = intent.getStringExtra(AlarmScheduler.EXTRA_DESC) ?: ""
    val open = android.app.PendingIntent.getActivity(
        ctx, 5, Intent(ctx, MainActivity::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
    val n = androidx.core.app.NotificationCompat.Builder(ctx, App.CHANNEL_ALARM)
        .setSmallIcon(R.drawable.ic_alarm)
        .setContentTitle(title)
        .setContentText("Apri l'app e concedi il permesso \"Sveglie e promemoria\"")
        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
        .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
        .setFullScreenIntent(open, true)
        .setAutoCancel(true)
        .build()
    try {
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(8, n)
    } catch (_: SecurityException) {
    }
}

/** Riavvio, aggiornamento app, cambio ora/fuso: riprogramma tutto. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "riavvio"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app aggiornata"
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> "cambio ora"
            else -> "evento di sistema"
        }
        val pending = goAsync()
        Thread {
            try {
                AlarmScheduler.sync(ctx, reason)
                SyncWork.ensure(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Promemoria: notifica con suono all'inizio di ogni evento del calendario. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val rawTitle = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: return
        val title = AlarmService.cleanTitle(rawTitle)
        val desc = intent.getStringExtra(AlarmScheduler.EXTRA_DESC) ?: ""
        val begin = intent.getLongExtra(AlarmScheduler.EXTRA_BEGIN, System.currentTimeMillis())
        val end = intent.getLongExtra(AlarmScheduler.EXTRA_END, 0L)
        val eventId = intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_ID, 0L)
        val code = intent.getIntExtra(AlarmScheduler.EXTRA_CODE, title.hashCode())

        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
        val time = if (end > begin) "${fmt.format(java.util.Date(begin))}–${fmt.format(java.util.Date(end))}"
        else fmt.format(java.util.Date(begin))

        // Toccando la notifica si apre l'evento nell'app Calendario.
        val open = if (eventId > 0) {
            Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(
                android.provider.CalendarContract.Events.CONTENT_URI, eventId
            )).putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
        } else Intent(ctx, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentPi = android.app.PendingIntent.getActivity(
            ctx, code, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(ctx, App.CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_event)
            .setContentTitle(title)
            .setContentText(if (desc.isBlank()) time else "$time · ${desc.lineSequence().first()}")
            .setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText(if (desc.isBlank()) time else "$time\n$desc")
            )
            .setSubText("Promemoria")
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setWhen(begin)
            .setShowWhen(true)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
        // Sparisce da sola quando l'evento finisce.
        val now = System.currentTimeMillis()
        if (end > now) builder.setTimeoutAfter(end - now)

        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(code, builder.build())
        } catch (_: SecurityException) {
        }
    }
}
