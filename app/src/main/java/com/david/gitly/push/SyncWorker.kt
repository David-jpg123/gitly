package com.david.gitly.push

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.david.gitly.Audience
import com.david.gitly.GitlyApp
import com.david.gitly.Store
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Sincronización breve de todas las cuentas: pide los sobres pendientes, avisa de los mensajes
 * nuevos y anuncia el timbre a quien no lo tenga. La lanzan el timbre o el sondeo cada 15 min.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GitlyApp
        val state = Store(app).load()
        val pushUrl = Push.url(app)
        var failed = false
        for (acc in state.accounts) {
            val m = app.messenger(acc.login, acc.token)
            if (!m.enabled) continue
            val res = withTimeoutOrNull(40_000) {
                m.syncDetailed { state.settings.privacy.messagesFrom != Audience.NOBODY }
            }
            if (res == null) { failed = true; continue }
            if (!app.inForeground && state.settings.notifyMessages) {
                res.messages.map { it.peer }.distinct().forEach { Notifications.show(app, acc.login, it) }
            }
            runCatching { m.announcePush(pushUrl) }.onFailure { Log.w("GitlyPush", "anuncio: ${it.message}") }
            if (!app.inForeground) m.shutdown()
        }
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "gitly_sync_periodic"
        private const val NOW = "gitly_sync_now"
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(net).build()
            // APPEND_OR_REPLACE: si suena el timbre mientras otra sincronización está a medias, se hace otra después.
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
