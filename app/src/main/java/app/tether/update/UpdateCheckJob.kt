package app.tether.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.tether.TetherApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Periodic background look at GitHub Releases, so a new version reaches the user as a notification
 * even when Tether isn't opened for days. Uses the platform JobScheduler (no WorkManager needed):
 * about twice a day, only with a network, kept across reboots. Scheduled only while Settings ›
 * Notifications › New Tether versions is on.
 */
class UpdateCheckJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (applicationContext as? TetherApp)?.container ?: return false
        work = container.scope.launch {
            try {
                container.updates.check(silent = true)?.join()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }

    companion object {
        private const val JOB_ID = 0x7E7E0100
        private const val PERIOD_MS = 12 * 3_600_000L

        /** Schedules the periodic check once; later calls leave the existing schedule alone. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS, PERIOD_MS / 4)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
