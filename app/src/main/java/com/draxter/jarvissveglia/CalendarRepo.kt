package com.draxter.jarvissveglia

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Legge il calendario del telefono (che Android tiene sincronizzato con Google Calendar).
 * Usa la tabella Instances, così anche gli eventi ricorrenti vengono espansi uno per uno.
 */
object CalendarRepo {
    private const val TAG = "CalendarRepo"

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun queryAlarms(ctx: Context): List<AlarmItem> {
        if (!hasPermission(ctx)) return emptyList()
        val keyword = Prefs.keyword(ctx)
        if (keyword.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        val end = now + Prefs.lookaheadDays(ctx) * 24L * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 60L * 60 * 1000)
            ContentUris.appendId(it, end)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val selection = "${CalendarContract.Instances.TITLE} LIKE ? AND " +
            "${CalendarContract.Instances.ALL_DAY} = 0 AND " +
            "${CalendarContract.Instances.VISIBLE} = 1"
        val args = arrayOf("%$keyword%")

        val out = mutableListOf<AlarmItem>()
        try {
            ctx.contentResolver.query(
                uri, projection, selection, args,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val status = if (c.isNull(5)) -1 else c.getInt(5)
                    if (status == CalendarContract.Instances.STATUS_CANCELED) continue
                    val begin = c.getLong(1)
                    if (begin <= now) continue
                    out += AlarmItem(
                        eventId = c.getLong(0),
                        begin = begin,
                        title = c.getString(2) ?: keyword,
                        description = c.getString(3) ?: "",
                        calendarName = c.getString(6) ?: ""
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lettura calendario fallita", e)
        }
        // Evita doppioni (stesso orario da calendari diversi).
        return out.distinctBy { it.begin / 60_000L }
    }

    /** Chiede ad Android di sincronizzare subito i calendari Google con il server. */
    fun requestGoogleSync(ctx: Context) {
        if (!hasPermission(ctx)) return
        try {
            val accounts = mutableSetOf<Pair<String, String>>()
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val type = c.getString(1) ?: continue
                    if (type == "com.google") accounts += name to type
                }
            }
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            accounts.forEach { (name, type) ->
                ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, extras)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Richiesta di sincronizzazione non riuscita", e)
        }
    }
}
