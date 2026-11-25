package com.draxter.jarvissveglia

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var keywordInput: EditText
    private lateinit var snoozeInput: EditText
    private lateinit var offsetInput: EditText
    private lateinit var nextAlarm: TextView
    private lateinit var alarmList: TextView
    private lateinit var syncInfo: TextView
    private lateinit var permBox: View
    private lateinit var permText: TextView
    private lateinit var permButton: Button
    private lateinit var diagText: TextView
    private lateinit var reminderList: TextView
    private lateinit var remindersSwitch: MaterialSwitch
    private lateinit var reminderOffsetInput: EditText
    private lateinit var enableCalendarsBtn: Button

    private val askWriteCalendar = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableCalendars()
        else Toast.makeText(this, "Senza questo permesso attivala dall'app Calendario", Toast.LENGTH_LONG).show()
    }

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.READ_CALENDAR] == true) {
            (application as App).registerObserver()
            CalendarRepo.requestGoogleSync(this)
        }
        refresh(sync = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enabledSwitch = findViewById(R.id.switchEnabled)
        keywordInput = findViewById(R.id.inputKeyword)
        snoozeInput = findViewById(R.id.inputSnooze)
        offsetInput = findViewById(R.id.inputOffset)
        nextAlarm = findViewById(R.id.nextAlarm)
        alarmList = findViewById(R.id.alarmList)
        syncInfo = findViewById(R.id.syncInfo)
        permBox = findViewById(R.id.permBox)
        permText = findViewById(R.id.permText)
        permButton = findViewById(R.id.permButton)
        diagText = findViewById(R.id.diagText)
        reminderList = findViewById(R.id.reminderList)
        remindersSwitch = findViewById(R.id.switchReminders)
        reminderOffsetInput = findViewById(R.id.inputReminderOffset)
        remindersSwitch.isChecked = Prefs.remindersEnabled(this)
        reminderOffsetInput.setText(Prefs.reminderOffsetMinutes(this).toString())
        remindersSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setRemindersEnabled(this, checked)
            refresh(sync = true)
        }
        findViewById<Button>(R.id.btnTestReminder).setOnClickListener {
            AlarmScheduler.testReminder(this, 10)
            Toast.makeText(this, "Il promemoria di prova arriva tra 10 secondi", Toast.LENGTH_LONG).show()
        }
        enableCalendarsBtn = findViewById(R.id.btnEnableCalendars)
        enableCalendarsBtn.setOnClickListener {
            if (CalendarRepo.hasWritePermission(this)) enableCalendars()
            else askWriteCalendar.launch(Manifest.permission.WRITE_CALENDAR)
        }

        enabledSwitch.isChecked = Prefs.enabled(this)
        keywordInput.setText(Prefs.keyword(this))
        snoozeInput.setText(Prefs.snoozeMinutes(this).toString())
        offsetInput.setText(Prefs.offsetMinutes(this).toString())

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            refresh(sync = true)
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setKeyword(this, keywordInput.text.toString().ifBlank { "Sveglia" })
            Prefs.setSnoozeMinutes(this, snoozeInput.text.toString().toIntOrNull() ?: 5)
            Prefs.setOffsetMinutes(this, offsetInput.text.toString().toIntOrNull() ?: 0)
            Prefs.setReminderOffsetMinutes(this, reminderOffsetInput.text.toString().toIntOrNull() ?: 0)
            reminderOffsetInput.setText(Prefs.reminderOffsetMinutes(this).toString())
            keywordInput.setText(Prefs.keyword(this))
            snoozeInput.setText(Prefs.snoozeMinutes(this).toString())
            offsetInput.setText(Prefs.offsetMinutes(this).toString())
            Toast.makeText(this, "Salvato", Toast.LENGTH_SHORT).show()
            refresh(sync = true)
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            CalendarRepo.requestGoogleSync(this)
            Toast.makeText(this, "Sincronizzo con Google Calendar…", Toast.LENGTH_SHORT).show()
            refresh(sync = true)
            // La sincronizzazione con Google richiede qualche secondo: ricontrolla dopo.
            nextAlarm.postDelayed({ refresh(sync = true) }, 8000)
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!AlarmScheduler.canScheduleExact(this)) {
                Toast.makeText(this, "Prima concedi il permesso per le sveglie", Toast.LENGTH_LONG).show()
            } else {
                AlarmScheduler.testAlarm(this, 10)
                Toast.makeText(this, "La sveglia di prova suona tra 10 secondi", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnCancelSnooze).setOnClickListener {
            AlarmScheduler.cancelSnooze(this)
            refresh(sync = false)
        }
        permButton.setOnClickListener { fixNextPermission() }
    }

    override fun onResume() {
        super.onResume()
        refresh(sync = true)
    }

    private fun refresh(sync: Boolean) {
        updatePermissionBox()
        io.execute {
            val items = if (sync) AlarmScheduler.sync(this, "app aperta") else Prefs.scheduled(this)
            val diag = CalendarRepo.diagnose(this)
            val disabled = CalendarRepo.disabledGoogleCalendars(this).isNotEmpty()
            runOnUiThread {
                render(items)
                diagText.text = diag
                enableCalendarsBtn.visibility = if (disabled) View.VISIBLE else View.GONE
            }
        }
    }

    private fun render(items: List<AlarmItem>) {
        val day = SimpleDateFormat("EEE d MMM", Locale.ITALY)
        val time = SimpleDateFormat("HH:mm", Locale.ITALY)
        val offset = Prefs.offsetMinutes(this) * 60_000L

        if (!Prefs.enabled(this)) {
            nextAlarm.text = "Sveglie disattivate"
        } else if (items.isEmpty()) {
            nextAlarm.text = if (CalendarRepo.hasPermission(this))
                "Nessun evento con \"${Prefs.keyword(this)}\" nei prossimi ${Prefs.lookaheadDays(this)} giorni.\nGuarda la sezione \"Cosa vede l'app\" qui sotto."
            else "Serve l'accesso al calendario"
        } else {
            val first = items.first()
            val at = first.begin - offset
            nextAlarm.text = "${relativeDay(at)} alle ${time.format(Date(at))}\n${countdown(at)}"
        }

        alarmList.text = if (items.isEmpty()) "—" else items.joinToString("\n") {
            val at = it.begin - offset
            "${day.format(Date(at))}  ·  ${time.format(Date(at))}   ${AlarmService.cleanTitle(it.title)}"
        }

        val reminders = Prefs.scheduledReminders(this)
        val remOffset = Prefs.reminderOffsetMinutes(this) * 60_000L
        reminderList.text = when {
            !Prefs.remindersEnabled(this) -> "Promemoria disattivati"
            reminders.isEmpty() -> "Nessun altro evento nelle prossime ${Prefs.REMINDER_DAYS * 24} ore"
            else -> reminders.take(12).joinToString("\n") {
                val at = it.begin - remOffset
                "${day.format(Date(at))}  ·  ${time.format(Date(at))}   ${AlarmService.cleanTitle(it.title)}"
            } + if (reminders.size > 12) "\n… e altri ${reminders.size - 12}" else ""
        }

        val snoozeAt = Prefs.snoozeAt(this)
        val snoozeBtn = findViewById<Button>(R.id.btnCancelSnooze)
        if (snoozeAt > System.currentTimeMillis()) {
            snoozeBtn.visibility = View.VISIBLE
            snoozeBtn.text = "Annulla posticipo delle ${time.format(Date(snoozeAt))}"
        } else snoozeBtn.visibility = View.GONE

        val last = Prefs.lastSync(this)
        syncInfo.text = if (last == 0L) "Mai sincronizzato" else
            "Ultimo aggiornamento: ${SimpleDateFormat("d MMM HH:mm:ss", Locale.ITALY).format(Date(last))} (${Prefs.lastSyncReason(this)})"
    }

    private fun enableCalendars() {
        io.execute {
            val n = CalendarRepo.enableGoogleCalendars(this)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (n > 0) "Calendari attivati: la sincronizzazione con Google parte ora" else "Nessun calendario da attivare",
                    Toast.LENGTH_LONG
                ).show()
                refresh(sync = true)
                nextAlarm.postDelayed({ refresh(sync = true) }, 10000)
                nextAlarm.postDelayed({ refresh(sync = true) }, 30000)
            }
        }
    }

    private fun relativeDay(ms: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        val diff = ((startOfDay(target) - startOfDay(today)) / (24 * 60 * 60 * 1000L)).toInt()
        return when (diff) {
            0 -> "Oggi"
            1 -> "Domani"
            else -> SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(Date(ms))
                .replaceFirstChar { it.uppercase() }
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun countdown(at: Long): String {
        val mins = ((at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "tra ${h} h ${m} min" else "tra ${m} min"
    }

    // ---- Permessi ----

    private enum class Missing { CALENDAR, NOTIFICATIONS, EXACT, FULLSCREEN, BATTERY }

    private fun missingPermissions(): List<Missing> {
        val out = mutableListOf<Missing>()
        if (!CalendarRepo.hasPermission(this)) out += Missing.CALENDAR
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) out += Missing.NOTIFICATIONS
        if (!AlarmScheduler.canScheduleExact(this)) out += Missing.EXACT
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) out += Missing.FULLSCREEN
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            out += Missing.BATTERY
        return out
    }

    private fun updatePermissionBox() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            permBox.visibility = View.GONE
            return
        }
        permBox.visibility = View.VISIBLE
        permText.text = when (missing.first()) {
            Missing.CALENDAR -> "Serve l'accesso al calendario per leggere gli eventi \"${Prefs.keyword(this)}\"."
            Missing.NOTIFICATIONS -> "Servono le notifiche per mostrare la sveglia."
            Missing.EXACT -> "Serve il permesso \"Sveglie e promemoria\" per suonare all'orario esatto."
            Missing.FULLSCREEN -> "Serve il permesso per mostrare la sveglia a schermo intero sul blocco schermo."
            Missing.BATTERY -> "Consigliato: togli l'ottimizzazione batteria, così la sveglia si aggiorna anche in background."
        } + if (missing.size > 1) "\n(${missing.size} permessi da sistemare)" else ""
        permButton.text = if (missing.first() == Missing.BATTERY) "Togli ottimizzazione" else "Concedi"
    }

    @SuppressLint("BatteryLife")
    private fun fixNextPermission() {
        when (missingPermissions().firstOrNull()) {
            Missing.CALENDAR, Missing.NOTIFICATIONS -> {
                val perms = mutableListOf(Manifest.permission.READ_CALENDAR)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                askPermissions.launch(perms.toTypedArray())
            }
            Missing.EXACT -> if (Build.VERSION.SDK_INT >= 31) startSafe(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
            Missing.FULLSCREEN -> if (Build.VERSION.SDK_INT >= 34) startSafe(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
            Missing.BATTERY -> startSafe(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
            null -> {}
        }
    }

    private fun startSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
