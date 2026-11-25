package com.david.gitly.push

import android.util.Log
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Aquí nos entrega el distribuidor la URL del timbre y cada toque de timbre. */
class PushServiceImpl : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i("GitlyPush", "timbre nuevo")
        Push.setUrl(applicationContext, endpoint.url)
        SyncWorker.runNow(applicationContext) // se lo cuenta a los amigos
    }

    override fun onMessage(message: PushMessage, instance: String) {
        SyncWorker.runNow(applicationContext)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w("GitlyPush", "registro fallido: $reason")
        Push.setUrl(applicationContext, null)
    }

    override fun onUnregistered(instance: String) {
        Push.setUrl(applicationContext, null)
        SyncWorker.runNow(applicationContext)
    }
}
