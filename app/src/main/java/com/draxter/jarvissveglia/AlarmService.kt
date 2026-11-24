package com.draxter.jarvissveglia

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/** Stato condiviso, così la schermata della sveglia si chiude quando il suono si ferma. */
object AlarmState {
    @Volatile var ringing = false
    @Volatile var title = ""
    @Volatile var description = ""
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    fun notifyChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it() }
    }
}

class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var volume = 0.15f

    private val rampUp = object : Runnable {
        override fun run() {
            volume = (volume + 0.05f).coerceAtMost(1f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 2000)
        }
    }
    private val timeout = Runnable { stopAlarm(snooze = true) } // dopo 10 minuti posticipa da sola

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAlarm(snooze = false)
            ACTION_SNOOZE -> stopAlarm(snooze = true)
            else -> startAlarm(intent)
        }
        return START_NOT_STICKY
    }

    private fun startAlarm(intent: Intent?) {
        val title = intent?.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Sveglia"
        val desc = intent?.getStringExtra(AlarmScheduler.EXTRA_DESC) ?: ""
        AlarmState.title = cleanTitle(title)
        AlarmState.description = desc
        AlarmState.ringing = true

        val notification = buildNotification(AlarmState.title, desc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JarvisSveglia:ring")
            .apply { acquire(11 * 60 * 1000L) }

        stopSound()
        startSound()
        startVibration()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 10 * 60 * 1000L)

        // Su alcuni telefoni la schermata va aperta a mano se lo schermo è già acceso.
        try {
            startActivity(alarmActivityIntent(this))
        } catch (_: Exception) {
        }
        AlarmState.notifyChanged()
    }

    private fun startSound() {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            volume = 0.15f
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            handler.postDelayed(rampUp, 2000)
        } catch (e: Exception) {
            player = null
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 600)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(pattern, 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    private fun stopSound() {
        handler.removeCallbacks(rampUp)
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
    }

    private fun stopAlarm(snooze: Boolean) {
        if (snooze && AlarmState.ringing) {
            AlarmScheduler.snooze(this, AlarmState.title, AlarmState.description)
        }
        handler.removeCallbacks(timeout)
        stopSound()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        AlarmState.ringing = false
        AlarmState.notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildNotification(title: String, desc: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 1, alarmActivityIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getService(
            this, 3, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(desc.ifBlank { "È ora di alzarsi" })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Posticipa ${Prefs.snoozeMinutes(this)} min", snooze)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.draxter.jarvissveglia.START"
        const val ACTION_STOP = "com.draxter.jarvissveglia.STOP"
        const val ACTION_SNOOZE = "com.draxter.jarvissveglia.SNOOZE"
        private const val NOTIF_ID = 7

        fun alarmActivityIntent(ctx: Context) = Intent(ctx, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        fun cleanTitle(t: String) = t.replace("[Jarvis Routine]", "").trim().ifBlank { "Sveglia" }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(action))
        }
    }
}
