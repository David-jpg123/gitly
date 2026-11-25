package com.david.gitly.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Timbre UnifiedPush. Un distribuidor (ntfy u otro) mantiene una sola conexión para todas las apps
 * y nos da una URL; quien nos escribe hace un POST vacío a esa URL y Gitly se despierta a sincronizar.
 * El mensaje nunca pasa por ahí: solo el aviso.
 */
object Push {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)

    fun url(ctx: Context): String? = prefs(ctx).getString("url", null)
    fun setUrl(ctx: Context, url: String?) { prefs(ctx).edit().putString("url", url).apply() }

    fun distributors(ctx: Context): List<String> = runCatching { UnifiedPush.getDistributors(ctx) }.getOrDefault(emptyList())
    fun active(ctx: Context): String? = runCatching { UnifiedPush.getAckDistributor(ctx) }.getOrNull()

    /** Si hay un distribuidor instalado lo usa (o el que ya se eligió) y pide/renueva la URL. */
    fun register(activity: android.app.Activity, onResult: (Boolean) -> Unit = {}) {
        if (active(activity) != null) { UnifiedPush.register(activity); onResult(true); return }
        if (distributors(activity).isEmpty()) { onResult(false); return }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity)
            onResult(ok)
        }
    }

    fun unregister(ctx: Context) {
        runCatching { UnifiedPush.unregister(ctx) }
        setUrl(ctx, null)
        SyncWorker.runNow(ctx) // avisa a los amigos de que ya no hay timbre
    }
}
