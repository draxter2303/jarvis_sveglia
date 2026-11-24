package com.draxter.jarvissveglia

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmActivity : AppCompatActivity() {

    private val onStateChange: () -> Unit = {
        runOnUiThread { if (!AlarmState.ringing) finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        findViewById<TextView>(R.id.alarmTime).text =
            SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())
        findViewById<TextView>(R.id.alarmTitle).text = AlarmState.title.ifBlank { "Sveglia" }
        findViewById<TextView>(R.id.alarmDesc).text = AlarmState.description

        val snoozeBtn = findViewById<Button>(R.id.btnSnooze)
        snoozeBtn.text = "Posticipa ${Prefs.snoozeMinutes(this)} min"
        snoozeBtn.setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_SNOOZE)
            finish()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AlarmService.send(this, AlarmService.ACTION_STOP)
            finish()
        }
        // Il tasto indietro non spegne la sveglia per sbaglio.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        AlarmState.addListener(onStateChange)
    }

    override fun onResume() {
        super.onResume()
        if (!AlarmState.ringing) finish()
    }

    override fun onDestroy() {
        AlarmState.removeListener(onStateChange)
        super.onDestroy()
    }
}
