package com.david.gitly.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import com.david.gitly.GitlyApp
import com.david.gitly.MainActivity
import com.david.gitly.R
import com.david.gitly.Store

/** Notificaciones de mensajes: una por conversación, con estilo de chat, responder y marcar leído. */
object Notifications {
    const val CHANNEL = "gitly_messages"
    const val EXTRA_ACCOUNT = "chat_account"
    const val EXTRA_PEER = "chat_peer"
    const val KEY_REPLY = "reply"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val name = ctx.getString(R.string.notif_channel_messages)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.notif_channel_messages_desc)
            enableVibration(true); setShowBadge(true)
        })
    }

    fun id(account: String, peer: String) = "${account.lowercase()}/${peer.lowercase()}".hashCode()

    private val avatars = HashMap<String, Bitmap?>()

    private suspend fun avatar(ctx: Context, login: String): Bitmap? {
        avatars[login]?.let { return it }
        val req = ImageRequest.Builder(ctx).data("https://avatars.githubusercontent.com/$login?s=128").allowHardware(false).size(128).build()
        val src = (ctx.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap ?: return null
        // recorte circular (MessagingStyle no lo hace en todas las versiones)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(src.width / 2f, src.height / 2f, minOf(src.width, src.height) / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        avatars[login] = out
        return out
    }

    /** Pinta (o retira si ya no quedan) la notificación de la conversación con [peer] en [account]. */
    suspend fun show(app: GitlyApp, account: String, peer: String) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val m = app.messenger(account, Store(app).load().accounts.firstOrNull { it.login.equals(account, true) }?.token)
        val d = m.store.data
        if (d.isBlocked(peer) || d.isMuted(peer)) { nm.cancel(id(account, peer)); return }
        val read = d.readUpTo[peer] ?: 0
        val unread = d.threads[peer].orEmpty().filter { !it.mine && it.ts > read }.takeLast(8)
        if (unread.isEmpty()) { nm.cancel(id(account, peer)); return }
        ensureChannel(app)

        val icon = runCatching { avatar(app, peer) }.getOrNull()?.let { IconCompat.createWithBitmap(it) }
        val person = Person.Builder().setName(peer).setKey(peer).apply { if (icon != null) setIcon(icon) }.build()
        val me = Person.Builder().setName(app.getString(R.string.notif_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
        for (msg in unread) {
            val text = msg.text.ifBlank { null } ?: msg.repo?.let { app.getString(R.string.notif_shared_repo, it.fullName) } ?: ""
            style.addMessage(text, msg.ts * 1000, person)
        }
        // Con varias cuentas, que se sepa a cuál ha llegado.
        if (Store(app).load().accounts.size > 1) style.conversationTitle = "$peer · @$account"

        val nid = id(account, peer)
        val open = PendingIntent.getActivity(app, nid,
            Intent(app, MainActivity::class.java).apply {
                putExtra(EXTRA_ACCOUNT, account); putExtra(EXTRA_PEER, peer)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun action(act: String) = Intent(app, ReplyReceiver::class.java).apply {
            action = act; putExtra(EXTRA_ACCOUNT, account); putExtra(EXTRA_PEER, peer)
        }
        // La respuesta tiene que ser MUTABLE para que el sistema le meta el texto escrito.
        val replyPi = PendingIntent.getBroadcast(app, nid, action(ReplyReceiver.REPLY),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val readPi = PendingIntent.getBroadcast(app, nid + 1, action(ReplyReceiver.READ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, app.getString(R.string.notif_reply), replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(app.getString(R.string.notif_reply_hint)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true).setShowsUserInterface(false).build()
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_notification, app.getString(R.string.notif_mark_read), readPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()

        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF3A5BA9.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setNumber(unread.size)
            .addAction(reply).addAction(markRead)
            .build()
        runCatching { nm.notify(nid, n) } // sin permiso POST_NOTIFICATIONS lanza SecurityException
    }

    fun cancel(ctx: Context, account: String, peer: String) {
        NotificationManagerCompat.from(ctx).cancel(id(account, peer))
    }
}
