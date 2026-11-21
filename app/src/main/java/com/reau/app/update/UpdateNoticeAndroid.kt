package com.reau.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.reau.app.BuildConfig
import com.reau.app.R
import com.reau.app.data.RideDiagnostics
import com.reau.app.settings.SettingsStore

/**
 * The daily job (P32A): Android's JobScheduler runs it about once a day, with a network ([JobInfo.NETWORK_TYPE_ANY]), and it does what the "Check now"
 * button does ([ReleaseCheck.fetch], the same request), then [UpdateNotice.decide]. No new dependency and no new permission: the job is not persisted across a
 * reboot (that would need RECEIVE_BOOT_COMPLETED), so [UpdateNoticeAndroid.sync] schedules it again whenever the app starts.
 */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        Thread({
            runCatching { UpdateNoticeAndroid.run(context) }
                .onFailure { RideDiagnostics.log("update", "update check (daily): crashed (${it.javaClass.simpleName})") }
            jobFinished(params, false)
        }, "update-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

internal object UpdateNoticeAndroid {
    private const val PREFS = "update_notice"
    private const val KEY_LAST_TOLD = "last_told_tag"

    /** The job in step with the switch: scheduled once when it is on, cancelled when it is off ([UpdateNotice.plan]). Cheap; the app calls it at its start and when the switch changes. */
    fun sync(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        when (UpdateNotice.plan(enabled, pending = scheduler.getPendingJob(UpdateNotice.JOB_ID) != null)) {
            UpdateNotice.Plan.SCHEDULE -> {
                val info = JobInfo.Builder(UpdateNotice.JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(UpdateNotice.PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                val result = scheduler.schedule(info)
                RideDiagnostics.log("update", "update check (daily): scheduled, ${if (result == JobScheduler.RESULT_SUCCESS) "ok" else "refused by Android"}")
            }
            UpdateNotice.Plan.CANCEL -> {
                scheduler.cancel(UpdateNotice.JOB_ID)
                RideDiagnostics.log("update", "update check (daily): cancelled, the switch is off")
            }
            UpdateNotice.Plan.KEEP, UpdateNotice.Plan.NOTHING -> Unit
        }
    }

    /** One run of the job, blocking (the job's own thread). */
    fun run(context: Context) {
        val store = SettingsStore.open(context)
        val enabled = store.load().updateNotice
        if (!enabled) {
            sync(context, false)
            RideDiagnostics.log("update", UpdateNotice.logLine(UpdateNotice.Decision.OFF, ReleaseCheck.Result.NoRelease))
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = ReleaseCheck.fetch("REau/${BuildConfig.VERSION_NAME}")
        val decision = UpdateNotice.decide(true, BuildConfig.VERSION_NAME, result, prefs.getString(KEY_LAST_TOLD, null))
        RideDiagnostics.log("update", UpdateNotice.logLine(decision, result))
        if (decision == UpdateNotice.Decision.NOTIFY && result is ReleaseCheck.Result.Latest && post(context, result)) {
            prefs.edit { putString(KEY_LAST_TOLD, result.tag) }
        }
    }

    /** Posts the notice; false when Android shows no notices from the app (the permission was refused), so the version is not counted as told. */
    private fun post(context: Context, latest: ReleaseCheck.Result.Latest): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            RideDiagnostics.log("update", "update check (daily): notifications are off for the app, ${latest.tag} not told")
            return false
        }
        manager.createNotificationChannel(
            NotificationChannel(UpdateNotice.CHANNEL_ID, context.getString(R.string.notif_update_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_update_channel_desc)
            },
        )
        // The page is the project's own address (ReleaseCheck keeps it so), opened in the browser by the tap.
        val open = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, latest.pageUrl.toUri()), PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(R.string.notif_update_text)
        val notification = Notification.Builder(context, UpdateNotice.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_update_title, UpdateNotice.versionLabel(latest.tag)))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_reau)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(UpdateNotice.NOTIFICATION_ID, notification)
        return true
    }
}
