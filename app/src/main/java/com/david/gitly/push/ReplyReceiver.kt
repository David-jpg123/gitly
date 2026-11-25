package com.david.gitly.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.david.gitly.GitlyApp
import com.david.gitly.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Acciones de la notificación: responder sin abrir la app y marcar como leído. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GitlyApp
        val account = intent.getStringExtra(Notifications.EXTRA_ACCOUNT) ?: return
        val peer = intent.getStringExtra(Notifications.EXTRA_PEER) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = Store(app).load().accounts.firstOrNull { it.login.equals(account, true) }?.token
                val m = app.messenger(account, token)
                if (intent.action == REPLY && !text.isNullOrEmpty()) withTimeoutOrNull(9_000) { m.send(peer, text) }
                // Responder implica haber leído lo anterior.
                m.markRead(peer, Store(app).load().settings.privacy.readReceipts)
                Notifications.cancel(app, account, peer)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val REPLY = "com.david.gitly.REPLY"
        const val READ = "com.david.gitly.MARK_READ"
    }
}
