package com.david.gitly.push

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gitly.GitlyApp
import com.david.gitly.chat.ChatMsg
import com.david.gitly.chat.SharedRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** La notificación de mensajes se pinta con sus acciones y "marcar como leído" la retira. */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext.applicationContext as GitlyApp
    private val account = "notif-test"
    private val peer = "octocat"

    init {
        inst.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun cleanup() {
        Notifications.cancel(app, account, peer)
        // el Messenger es único por proceso: vaciar también lo que tiene en memoria
        app.messenger(account, null).store.run { update { com.david.gitly.chat.ChatData() }; delete() }
    }

    private fun active() = app.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == Notifications.id(account, peer) }

    @Test fun showsConversationAndMarkReadClearsIt() = runBlocking {
        val m = app.messenger(account, null)
        val now = System.currentTimeMillis() / 1000
        m.store.put(ChatMsg("t1", peer, false, "¿Has visto este launcher?", now - 30))
        m.store.put(ChatMsg("t2", peer, false, "", now, SharedRepo("MM2-0/Kvaesitso", "launcher", 5400, "Kotlin")))
        Notifications.show(app, account, peer)

        val sbn = active()
        assertTrue("la notificación está publicada", sbn != null)
        val n = sbn!!.notification
        assertEquals(Notifications.CHANNEL, n.channelId)
        assertEquals("responder y marcar leído", 2, n.actions.size)
        assertTrue("la acción de responder lleva campo de texto", n.actions[0].remoteInputs?.isNotEmpty() == true)
        val texts = n.extras.getParcelableArray(androidx.core.app.NotificationCompat.EXTRA_MESSAGES)!!
            .map { (it as android.os.Bundle).getCharSequence("text").toString() }
        assertEquals(listOf("¿Has visto este launcher?", app.getString(com.david.gitly.R.string.notif_shared_repo, "MM2-0/Kvaesitso")), texts)
        // -e hold <ms>: deja la notificación a la vista para hacer una captura
        delay(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: 0L)

        // "Marcar como leído" desde la notificación
        app.sendBroadcast(Intent(app, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.READ
            putExtra(Notifications.EXTRA_ACCOUNT, account); putExtra(Notifications.EXTRA_PEER, peer)
        })
        var gone = false
        repeat(20) { if (active() == null) { gone = true; return@repeat }; delay(250) }
        assertTrue("marcar como leído la quita", gone)
        assertEquals(now, m.store.data.readUpTo[peer])
    }

    @Test fun mutedAndBlockedDoNotNotify() = runBlocking {
        val m = app.messenger(account, null)
        m.store.put(ChatMsg("t3", peer, false, "hola", System.currentTimeMillis() / 1000))
        m.setMuted(peer, System.currentTimeMillis() + 3_600_000)
        Notifications.show(app, account, peer)
        assertTrue("silenciado: sin notificación", active() == null)
        m.setMuted(peer, null)
        Notifications.show(app, account, peer)
        assertTrue("sin silencio: sí", active() != null)
        m.setBlocked(peer, true)
        Notifications.show(app, account, peer)
        assertTrue("bloqueado: la retira", active() == null)
    }
}
