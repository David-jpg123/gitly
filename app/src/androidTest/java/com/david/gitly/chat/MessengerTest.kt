package com.david.gitly.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gitly.core.GitHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ida y vuelta real por los relays públicos con dos identidades de prueba. GitHub se sustituye
 * (gists y seguidores) para no tocar ninguna cuenta real.
 */
@RunWith(AndroidJUnit4::class)
class MessengerTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val alice = Messenger(ctx, "alice-test", GitHub(null))
    private val bob = Messenger(ctx, "bob-test", GitHub(null))
    private val carol = Messenger(ctx, "carol-test", GitHub(null))

    init {
        listOf(alice, bob, carol).forEach { it.useTestKey() }
        val keys = mapOf("alice-test" to alice.pubkeyHex, "bob-test" to bob.pubkeyHex, "carol-test" to carol.pubkeyHex)
        listOf(alice, bob, carol).forEach { m -> m.resolveOverride = { keys[it.lowercase()] } }
        // alice y bob se siguen mutuamente; carol no es mutua de bob.
        alice.mutualOverride = { it == "bob-test" }
        bob.mutualOverride = { it == "alice-test" }
        carol.mutualOverride = { true }
    }

    @After fun cleanup(): Unit = runBlocking {
        listOf(alice, bob, carol).forEach { it.shutdown(); it.store.delete() }
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("msgkey-") && it.name.contains("-test") }?.forEach { it.delete() }
        Unit
    }

    private suspend fun waitFor(m: Messenger, peer: String, cond: (List<ChatMsg>) -> Boolean): List<ChatMsg> {
        repeat(12) {
            m.sync()
            val msgs = m.store.data.threads[peer].orEmpty()
            if (cond(msgs)) return msgs
            delay(2_000)
        }
        return m.store.data.threads[peer].orEmpty()
    }

    @Test fun textAndRepoRoundTrip() = runBlocking {
        val stamp = System.currentTimeMillis().toString()
        val sent = alice.send("bob-test", "hola $stamp")
        assertEquals("el envío debe llegar a algún relay", MsgStatus.SENT, sent.status)
        val shared = alice.send("bob-test", "", SharedRepo("MM2-0/Kvaesitso", "A search-focused Android launcher", 5400, "Kotlin"))
        assertEquals(MsgStatus.SENT, shared.status)

        val got = waitFor(bob, "alice-test") { l -> l.any { it.text == "hola $stamp" } && l.any { it.repo != null } }
        val text = got.firstOrNull { it.text == "hola $stamp" }
        val repo = got.firstOrNull { it.repo?.fullName == "MM2-0/Kvaesitso" }
        assertTrue("bob debe recibir el texto: $got", text != null && !text.mine)
        assertTrue("bob debe recibir la tarjeta del repo: $got", repo != null && repo.repo!!.stars == 5400)
        println("GITLY-TEST ok: ${got.size} mensajes, ids ${got.map { it.id.take(8) }}")
    }

    @Test fun nonMutualIsDropped() = runBlocking {
        val stamp = System.currentTimeMillis().toString()
        assertEquals(MsgStatus.SENT, carol.send("bob-test", "spam $stamp").status)
        // control: un mensaje de alice en la misma ventana sí debe entrar
        assertEquals(MsgStatus.SENT, alice.send("bob-test", "ok $stamp").status)
        val fromAlice = waitFor(bob, "alice-test") { l -> l.any { it.text == "ok $stamp" } }
        assertTrue("el de alice llega", fromAlice.any { it.text == "ok $stamp" })
        assertTrue("el de carol (no mutua) se descarta", bob.store.data.threads["carol-test"].isNullOrEmpty())
    }

    private suspend fun waitRepost(key: String, present: Boolean, public: Boolean): Boolean {
        repeat(12) {
            if (public) bob.syncPublicReposts(mapOf("alice-test" to alice.pubkeyHex!!)) else bob.sync()
            if ((key in bob.store.data.reposts) == present) return true
            delay(2_000)
        }
        return false
    }

    @Test fun privateRepostReachesFriendAndUndoRemovesIt() = runBlocking {
        val repo = SharedRepo("octo-test/repo-${System.currentTimeMillis()}", "test", 7, "Kotlin")
        val key = "alice-test|${repo.fullName.lowercase()}"
        assertEquals(1, alice.repost(repo, RepostScope.FRIENDS, listOf("bob-test")))
        assertTrue("alice lo ve en sus reposts", repo.fullName.lowercase() in alice.store.data.myReposts)
        assertTrue("bob recibe el repost privado", waitRepost(key, present = true, public = false))
        assertEquals(1, alice.repost(repo, RepostScope.FRIENDS, listOf("bob-test"), undo = true))
        assertTrue("deshacer lo quita en bob", waitRepost(key, present = false, public = false))
        assertTrue(repo.fullName.lowercase() !in alice.store.data.myReposts)
    }

    @Test fun publicRepostIsReadByFriends() = runBlocking {
        val repo = SharedRepo("octo-test/pub-${System.currentTimeMillis()}", "test", 3, null)
        val key = "alice-test|${repo.fullName.lowercase()}"
        assertEquals(1, alice.repost(repo, RepostScope.PUBLIC, emptyList()))
        assertTrue("bob lee el repost público de alice", waitRepost(key, present = true, public = true))
        assertEquals(1, alice.repost(repo, RepostScope.PUBLIC, emptyList(), undo = true))
        assertTrue("el borrado público también llega", waitRepost(key, present = false, public = true))
    }

    @Test fun onlyMeStaysLocal() = runBlocking {
        val repo = SharedRepo("octo-test/local-${System.currentTimeMillis()}")
        assertEquals(1, alice.repost(repo, RepostScope.ONLY_ME, listOf("bob-test")))
        assertTrue(repo.fullName.lowercase() in alice.store.data.myReposts)
        bob.sync()
        assertTrue(bob.store.data.reposts.keys.none { it.endsWith(repo.fullName.lowercase()) })
    }

    @Test fun profileShowsPublicRepostsToAnyone() = runBlocking {
        val repo = SharedRepo("octo-test/profile-${System.currentTimeMillis()}", "perfil", 9, "Rust")
        assertEquals(1, alice.repost(repo, RepostScope.PUBLIC, emptyList()))
        // carol no es mutua de alice, pero el repost es público: debe verlo en su perfil
        var seen = false
        repeat(10) {
            if (carol.fetchUserReposts("alice-test").orEmpty().any { it.repo.fullName == repo.fullName }) { seen = true; return@repeat }
            delay(2_000)
        }
        assertTrue("cualquiera ve los reposts públicos en el perfil", seen)
        alice.repost(repo, RepostScope.PUBLIC, emptyList(), undo = true)
        var gone = false
        repeat(10) {
            if (carol.fetchUserReposts("alice-test").orEmpty().none { it.repo.fullName == repo.fullName }) { gone = true; return@repeat }
            delay(2_000)
        }
        assertTrue("al quitarlo desaparece del perfil", gone)
    }

    @Test fun pushUrlTravelsEncryptedAndDoorbellRings() = runBlocking {
        // 1) alice cuenta su timbre a bob (cifrado): bob lo guarda; carol (no mutua) no lo recibe.
        val aliceUrl = "https://ntfy.sh/gitlytest-a${System.currentTimeMillis()}?up=1"
        assertEquals(1, alice.announcePush(aliceUrl, listOf("bob-test")))
        assertEquals("no se reenvía si no cambia", 0, alice.announcePush(aliceUrl, listOf("bob-test")))
        var got: String? = null
        repeat(12) {
            bob.sync(); got = bob.store.data.peerPush["alice-test"]
            if (got != null) return@repeat
            delay(2_000)
        }
        assertEquals(aliceUrl, got)

        // 2) bob escribe a alice → toca el timbre de alice de verdad (POST a ntfy.sh).
        val topic = "gitlytest-${java.util.UUID.randomUUID().toString().take(12)}"
        bob.store.update { it.copy(peerPush = it.peerPush + ("alice-test" to "https://ntfy.sh/$topic")) }
        assertEquals(MsgStatus.SENT, bob.send("alice-test", "ding").status)
        val http = okhttp3.OkHttpClient()
        var rang = false
        repeat(10) {
            val body = runCatching {
                http.newCall(okhttp3.Request.Builder().url("https://ntfy.sh/$topic/json?poll=1&since=all").build()).execute().use { it.body!!.string() }
            }.getOrDefault("")
            if ("\"event\":\"message\"" in body) { rang = true; return@repeat }
            delay(1_000)
        }
        assertTrue("el timbre de alice ha sonado en ntfy", rang)

        // 3) alice sincroniza y syncDetailed dice qué mensaje es nuevo (para la notificación).
        val fresh = ArrayList<ChatMsg>()
        for (i in 0 until 12) {
            fresh += alice.syncDetailed()?.messages.orEmpty()
            if (fresh.any { it.text == "ding" }) break
            delay(2_000)
        }
        assertTrue("syncDetailed devuelve el mensaje nuevo: $fresh", fresh.any { it.text == "ding" && it.peer == "bob-test" })

        // 4) retirar el timbre también viaja.
        assertEquals(1, alice.announcePush(null, listOf("bob-test")))
        var cleared = false
        repeat(12) {
            bob.sync(); if ("alice-test" !in bob.store.data.peerPush) { cleared = true; return@repeat }
            delay(2_000)
        }
        assertTrue("bob olvida el timbre retirado", cleared)
    }

    @Test fun blockedPeerIsDroppedAndCannotBeWritten() = runBlocking {
        val stamp = System.currentTimeMillis().toString()
        bob.setBlocked("alice-test", true)
        assertEquals(ChatAvailability.BLOCKED, bob.availability("alice-test"))
        assertTrue("no se puede escribir a un bloqueado", runCatching { bob.send("alice-test", "x") }.isFailure)
        assertEquals(MsgStatus.SENT, alice.send("bob-test", "bloqueado $stamp").status)
        // control en la misma ventana: carol→bob no cuenta (no mutua), así que esperamos un tiempo fijo
        repeat(4) { bob.sync(); delay(2_000) }
        assertTrue("el mensaje del bloqueado no entra", bob.store.data.threads["alice-test"].orEmpty().none { it.text == "bloqueado $stamp" })

        bob.setBlocked("alice-test", false)
        assertEquals(MsgStatus.SENT, alice.send("bob-test", "ya no $stamp").status)
        val got = waitFor(bob, "alice-test") { l -> l.any { it.text == "ya no $stamp" } }
        assertTrue("al desbloquear vuelven a llegar", got.any { it.text == "ya no $stamp" })
        assertTrue("lo enviado mientras estaba bloqueado no reaparece", got.none { it.text == "bloqueado $stamp" })
    }

    @Test fun readReceiptReachesSenderOnlyWhenEnabled() = runBlocking {
        val stamp = System.currentTimeMillis().toString()
        val sent = alice.send("bob-test", "¿lo lees? $stamp")
        waitFor(bob, "alice-test") { l -> l.any { it.text == "¿lo lees? $stamp" } }

        // con las confirmaciones desactivadas no sale nada
        bob.markRead("alice-test", receipts = false)
        assertEquals(null, bob.store.data.receiptSent["alice-test"])

        bob.markRead("alice-test", receipts = true)
        var seenUpTo: Long? = null
        for (i in 0 until 12) {
            alice.sync()
            seenUpTo = alice.store.data.peerReadUpTo["bob-test"]
            if (seenUpTo != null && seenUpTo >= sent.ts) break
            delay(2_000)
        }
        assertTrue("alice sabe que bob lo ha leído ($seenUpTo ≥ ${sent.ts})", seenUpTo != null && seenUpTo >= sent.ts)
        assertTrue("bob recuerda que ya lo confirmó", (bob.store.data.receiptSent["alice-test"] ?: 0) >= sent.ts)
        assertTrue("la confirmación no aparece como mensaje", alice.store.data.threads["bob-test"].orEmpty().all { it.text.isNotEmpty() || it.repo != null })
    }
}
