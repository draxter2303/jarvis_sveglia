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
        ContextCompat.startForegroundService(ctx, svc)
        // Programma subito le sveglie successive.
        SyncWork.syncSoon(ctx, "dopo una sveglia")
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
