package com.draxter.jarvissveglia

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Risincronizza le sveglie dal calendario. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AlarmScheduler.sync(applicationContext, inputData.getString("reason") ?: "periodica")
        return Result.success()
    }
}

/**
 * Parte appena cambia qualcosa nel calendario del telefono (cioè dopo ogni sincronizzazione
 * con Google Calendar che modifica un evento), poi si rimette in ascolto.
 */
class CalendarChangeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        AlarmScheduler.sync(applicationContext, "calendario modificato")
        SyncWork.listenForCalendarChanges(applicationContext)
        return Result.success()
    }
}

object SyncWork {
    private const val PERIODIC = "periodic-sync"
    private const val OBSERVER = "calendar-observer"
    private const val ONESHOT = "oneshot-sync"

    fun ensure(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
        )
        listenForCalendarChanges(ctx, ExistingWorkPolicy.KEEP)
    }

    fun listenForCalendarChanges(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(3, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            OBSERVER, policy,
            OneTimeWorkRequestBuilder<CalendarChangeWorker>().setConstraints(constraints).build()
        )
    }

    fun syncSoon(ctx: Context, reason: String) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONESHOT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.workDataOf("reason" to reason))
                .build()
        )
    }
}
