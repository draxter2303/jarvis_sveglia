package com.draxter.jarvissveglia

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    const val SNOOZE_REQUEST_CODE = 42

    const val EXTRA_TITLE = "title"
    const val EXTRA_DESC = "desc"
    const val EXTRA_BEGIN = "begin"
    const val EXTRA_SNOOZE = "snooze"
    const val EXTRA_END = "end"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_CODE = "code"
    const val TEST_REMINDER_CODE = -42

    private val lock = Any()

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /**
     * Rilegge il calendario e allinea le sveglie: aggiunge le nuove, sposta quelle cambiate,
     * cancella quelle eliminate.
     */
    fun sync(ctx: Context, reason: String): List<AlarmItem> = synchronized(lock) {
        val app = ctx.applicationContext
        val old = Prefs.scheduled(app)
        val offsetMs = Prefs.offsetMinutes(app) * 60_000L
        val now = System.currentTimeMillis()
        val fresh = if (Prefs.enabled(app)) {
            CalendarRepo.queryAlarms(app).filter { it.begin - offsetMs > now + 5_000 }
        } else emptyList()

        val freshCodes = fresh.map { it.requestCode }.toSet()
        old.filter { it.requestCode !in freshCodes }.forEach { cancel(app, it.requestCode) }
        fresh.forEach { schedule(app, it, it.begin - offsetMs) }

        Prefs.saveScheduled(app, fresh, reason)
        syncReminders(app)
        Log.i(TAG, "Sync ($reason): ${fresh.size} sveglie")
        fresh
    }

    /** Promemoria per tutti gli altri eventi del calendario. */
    private fun syncReminders(ctx: Context) {
        val old = Prefs.scheduledReminders(ctx)
        val offsetMs = Prefs.reminderOffsetMinutes(ctx) * 60_000L
        val now = System.currentTimeMillis()
        val fresh = if (Prefs.remindersEnabled(ctx)) {
            CalendarRepo.queryReminders(ctx).filter { it.begin - offsetMs > now + 5_000 }
        } else emptyList()

        val freshCodes = fresh.map { it.reminderCode }.toSet()
        old.filter { it.reminderCode !in freshCodes }.forEach { cancelReminder(ctx, it.reminderCode) }
        fresh.forEach { scheduleReminder(ctx, it, it.begin - offsetMs) }
        Prefs.saveReminders(ctx, fresh)
    }

    private fun reminderIntent(ctx: Context, code: Int, item: AlarmItem?): PendingIntent? {
        val intent = Intent(ctx, ReminderReceiver::class.java)
        if (item != null) {
            intent.putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_DESC, item.description)
                .putExtra(EXTRA_BEGIN, item.begin)
                .putExtra(EXTRA_END, item.end)
                .putExtra(EXTRA_EVENT_ID, item.eventId)
                .putExtra(EXTRA_CODE, code)
        }
        val flags = if (item == null) PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, code, intent, flags)
    }

    fun scheduleReminder(ctx: Context, item: AlarmItem, triggerAt: Long, code: Int = item.reminderCode) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val op = reminderIntent(ctx, code, item) ?: return
        try {
            // Esatto ma senza l'icona della sveglia nella barra di stato.
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
        }
    }

    private fun cancelReminder(ctx: Context, code: Int) {
        val pi = reminderIntent(ctx, code, null) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    fun testReminder(ctx: Context, seconds: Int = 10) {
        val at = System.currentTimeMillis() + seconds * 1000L
        scheduleReminder(
            ctx,
            AlarmItem(0, at, "Promemoria di prova", "Così ti arriveranno gli eventi del calendario.", "", at + 30 * 60_000L),
            at, TEST_REMINDER_CODE
        )
    }

    fun schedule(ctx: Context, item: AlarmItem, triggerAt: Long, snooze: Boolean = false) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val code = if (snooze) SNOOZE_REQUEST_CODE else item.requestCode
        val op = PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_DESC, item.description)
                .putExtra(EXTRA_BEGIN, item.begin)
                .putExtra(EXTRA_SNOOZE, snooze),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (canScheduleExact(ctx)) {
                // setAlarmClock: massima priorità, scatta anche in Doze e mostra l'icona sveglia.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), op)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permesso sveglie esatte mancante", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
        }
    }

    fun cancel(ctx: Context, requestCode: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, requestCode, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun snooze(ctx: Context, title: String, desc: String) {
        val minutes = Prefs.snoozeMinutes(ctx)
        val at = System.currentTimeMillis() + minutes * 60_000L
        schedule(ctx, AlarmItem(0, at, title, desc, ""), at, snooze = true)
        Prefs.setSnoozeAt(ctx, at)
    }

    fun cancelSnooze(ctx: Context) {
        cancel(ctx, SNOOZE_REQUEST_CODE)
        Prefs.setSnoozeAt(ctx, 0L)
    }

    /** Sveglia di prova tra pochi secondi. */
    fun testAlarm(ctx: Context, seconds: Int = 10) {
        val at = System.currentTimeMillis() + seconds * 1000L
        schedule(ctx, AlarmItem(0, at, "Sveglia di prova", "Se senti questa, funziona tutto.", ""), at, snooze = true)
    }
}
