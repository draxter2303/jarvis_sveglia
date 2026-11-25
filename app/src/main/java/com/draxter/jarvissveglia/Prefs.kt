package com.draxter.jarvissveglia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AlarmItem(
    val eventId: Long,
    val begin: Long,
    val title: String,
    val description: String,
    val calendarName: String,
    val end: Long = 0L
) {
    /** Codice per il PendingIntent del promemoria (negativo: non si scontra con le sveglie). */
    val reminderCode: Int
        get() = -requestCode

    /** Codice univoco per il PendingIntent di questa sveglia. */
    val requestCode: Int
        get() = (((eventId * 1_000_003L) xor (begin / 60_000L)) and 0x3FFFFFFF).toInt() + 1000

    fun toJson(): JSONObject = JSONObject()
        .put("e", eventId).put("b", begin).put("t", title)
        .put("d", description).put("c", calendarName).put("n", end)

    companion object {
        fun fromJson(o: JSONObject) = AlarmItem(
            o.optLong("e"), o.optLong("b"), o.optString("t"),
            o.optString("d"), o.optString("c"), o.optLong("n")
        )
    }
}

object Prefs {
    private const val FILE = "jarvis_sveglia"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    /** Parola da cercare nel titolo degli eventi (senza distinzione maiuscole/minuscole). */
    fun keyword(ctx: Context) = sp(ctx).getString("keyword", "Sveglia") ?: "Sveglia"
    fun setKeyword(ctx: Context, v: String) = sp(ctx).edit().putString("keyword", v.trim()).apply()

    fun snoozeMinutes(ctx: Context) = sp(ctx).getInt("snooze", 5)
    fun setSnoozeMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("snooze", v.coerceIn(1, 60)).apply()

    /** Minuti di anticipo rispetto all'inizio dell'evento. */
    fun offsetMinutes(ctx: Context) = sp(ctx).getInt("offset", 0)
    fun setOffsetMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("offset", v.coerceIn(0, 120)).apply()

    fun lookaheadDays(ctx: Context) = sp(ctx).getInt("days", 14)
    fun setLookaheadDays(ctx: Context, v: Int) = sp(ctx).edit().putInt("days", v.coerceIn(1, 60)).apply()

    fun lastSync(ctx: Context) = sp(ctx).getLong("last_sync", 0L)
    fun lastSyncReason(ctx: Context) = sp(ctx).getString("last_reason", "") ?: ""

    fun scheduled(ctx: Context): List<AlarmItem> {
        val raw = sp(ctx).getString("scheduled", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AlarmItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveScheduled(ctx: Context, items: List<AlarmItem>, reason: String) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        sp(ctx).edit()
            .putString("scheduled", arr.toString())
            .putLong("last_sync", System.currentTimeMillis())
            .putString("last_reason", reason)
            .apply()
    }

    // ---- Promemoria (tutti gli altri eventi) ----
    fun remindersEnabled(ctx: Context) = sp(ctx).getBoolean("rem_enabled", true)
    fun setRemindersEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("rem_enabled", v).apply()

    fun reminderOffsetMinutes(ctx: Context) = sp(ctx).getInt("rem_offset", 0)
    fun setReminderOffsetMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("rem_offset", v.coerceIn(0, 120)).apply()

    /** I promemoria si programmano solo per i prossimi giorni (si riaggiornano di continuo). */
    const val REMINDER_DAYS = 2

    private fun loadList(ctx: Context, key: String): List<AlarmItem> {
        val raw = sp(ctx).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AlarmItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun scheduledReminders(ctx: Context) = loadList(ctx, "reminders")
    fun saveReminders(ctx: Context, items: List<AlarmItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("reminders", arr.toString()).apply()
    }

    fun snoozeAt(ctx: Context) = sp(ctx).getLong("snooze_at", 0L)
    fun setSnoozeAt(ctx: Context, v: Long) = sp(ctx).edit().putLong("snooze_at", v).apply()
}
