package com.draxter.jarvissveglia

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val visible: Boolean,
    val syncEvents: Boolean
) {
    val isGoogle get() = accountType == "com.google"
}

/**
 * Legge il calendario del telefono (che Android tiene sincronizzato con Google Calendar).
 * Usa la tabella Instances, così anche gli eventi ricorrenti vengono espansi uno per uno.
 */
object CalendarRepo {
    private const val TAG = "CalendarRepo"

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.WRITE_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    private fun instancesUri(ctx: Context, from: Long) =
        CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, from + Prefs.lookaheadDays(ctx) * 24L * 60 * 60 * 1000)
        }.build()

    fun queryAlarms(ctx: Context): List<AlarmItem> {
        if (!hasPermission(ctx)) return emptyList()
        val keyword = Prefs.keyword(ctx).trim().lowercase()
        if (keyword.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val out = mutableListOf<AlarmItem>()
        try {
            // Nessun filtro SQL sul titolo: il confronto lo facciamo qui, ignorando maiuscole e accenti.
            ctx.contentResolver.query(
                instancesUri(ctx, now - 60L * 60 * 1000), projection, null, null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(2) ?: continue
                    if (!title.lowercase().contains(keyword)) continue
                    if (c.getInt(4) == 1) continue // eventi di tutto il giorno
                    val status = if (c.isNull(5)) -1 else c.getInt(5)
                    if (status == CalendarContract.Instances.STATUS_CANCELED) continue
                    val begin = c.getLong(1)
                    if (begin <= now) continue
                    out += AlarmItem(
                        eventId = c.getLong(0),
                        begin = begin,
                        title = title,
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

    fun calendars(ctx: Context): List<CalendarInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.VISIBLE,
                    CalendarContract.Calendars.SYNC_EVENTS
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "?",
                        account = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        visible = c.getInt(4) == 1,
                        syncEvents = c.getInt(5) == 1
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lettura calendari fallita", e)
        }
        return out
    }

    /** Testo di diagnostica: cosa vede l'app nel calendario del telefono. */
    fun diagnose(ctx: Context): String {
        if (!hasPermission(ctx)) return "Permesso calendario NON concesso."
        val cals = calendars(ctx)
        if (cals.isEmpty()) return "Il telefono non ha nessun calendario.\nAggiungi il tuo account Google in Impostazioni > Account e attiva la sincronizzazione di Calendario."

        val keyword = Prefs.keyword(ctx).trim().lowercase()
        val total = mutableMapOf<Long, Int>()
        val matching = mutableMapOf<Long, Int>()
        try {
            ctx.contentResolver.query(
                instancesUri(ctx, System.currentTimeMillis()),
                arrayOf(CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    total[id] = (total[id] ?: 0) + 1
                    if ((c.getString(1) ?: "").lowercase().contains(keyword)) {
                        matching[id] = (matching[id] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            return "Errore leggendo gli eventi: ${e.message}"
        }

        val days = Prefs.lookaheadDays(ctx)
        val sb = StringBuilder()
        sb.append("Account Google sul telefono: ")
        val googleAccounts = cals.filter { it.isGoogle }.map { it.account }.distinct()
        sb.append(if (googleAccounts.isEmpty()) "NESSUNO" else googleAccounts.joinToString())
        sb.append("\n\nCalendari (eventi nei prossimi $days giorni / con \"${Prefs.keyword(ctx)}\"):\n")
        cals.sortedByDescending { it.isGoogle }.forEach { cal ->
            val flags = buildList {
                if (!cal.syncEvents) add("NON sincronizzato")
                if (!cal.visible) add("nascosto")
            }
            sb.append("• ${cal.name}  [${total[cal.id] ?: 0} / ${matching[cal.id] ?: 0}]")
            if (cal.account.isNotBlank() && cal.account != cal.name) sb.append("\n   ${cal.account}")
            if (flags.isNotEmpty()) sb.append("  ⚠ ${flags.joinToString()}")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    /** Calendari Google con la sincronizzazione spenta o nascosti. */
    fun disabledGoogleCalendars(ctx: Context) =
        calendars(ctx).filter { it.isGoogle && (!it.syncEvents || !it.visible) }

    /** Accende sincronizzazione e visibilità di tutti i calendari Google. */
    fun enableGoogleCalendars(ctx: Context): Int {
        if (!hasWritePermission(ctx)) return -1
        var changed = 0
        disabledGoogleCalendars(ctx).forEach { cal ->
            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                    put(CalendarContract.Calendars.VISIBLE, 1)
                }
                changed += ctx.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, cal.id),
                    values, null, null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Impossibile attivare ${cal.name}", e)
            }
        }
        requestGoogleSync(ctx)
        return changed
    }

    /** Chiede ad Android di sincronizzare subito i calendari Google con il server. */
    fun requestGoogleSync(ctx: Context) {
        if (!hasPermission(ctx)) return
        try {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            calendars(ctx).filter { it.isGoogle }.map { it.account }.distinct().forEach { name ->
                val account = Account(name, "com.google")
                ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
                ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Richiesta di sincronizzazione non riuscita", e)
        }
    }
}
