package com.david.gitly.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.gitly.Account
import com.david.gitly.AppState
import com.david.gitly.GitlyApp
import com.david.gitly.Store
import com.david.gitly.chat.ChatMsg
import com.david.gitly.chat.MsgStatus
import com.david.gitly.chat.SharedRepo
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nostrdevkit.sdk.Keys

/**
 * No es una prueba: prepara la app para hacer las capturas del README y la deja abierta.
 *
 *   adb shell am instrument -w -e class com.david.gitly.demo.ScreenshotTour \
 *       -e login <login> -e token <token> -e hold 900000 com.david.gitly.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Inicia sesión con el token, activa los mensajes con una clave local (sin publicar gist) y siembra
 * una conversación de demostración con octocat, la cuenta mascota de GitHub. Nada sale de este dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @Test fun prepare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val login = args.getString("login")
        val token = args.getString("token")
        assumeTrue("solo con -e login y -e token", login != null && token != null)
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as GitlyApp
        Store(app).save(AppState(accounts = listOf(Account(login!!, token!!)), active = login))

        val m = app.messenger(login, token)
        m.useTestKey()
        val fake = Keys.generate().publicKey().toHex()
        m.resolveOverride = { u -> if (u.equals("octocat", true)) fake else null }
        m.mutualOverride = { it.equals("octocat", true) }

        val now = System.currentTimeMillis() / 1000
        val peer = "octocat"
        listOf(
            ChatMsg("d1", peer, false, "Found a launcher you'll love 👀", now - 3600),
            ChatMsg("d2", peer, false, "", now - 3590, SharedRepo("MM2-0/Kvaesitso", "A search-focused, free and open source launcher for Android", 5400, "Kotlin")),
            ChatMsg("d3", peer, true, "Installed it straight from the release, right inside Gitly", now - 1200, status = MsgStatus.SENT),
            ChatMsg("d4", peer, false, "Told you. Reposting it so the others see it too 🔁", now - 900),
            ChatMsg("d5", peer, true, "Starred ⭐", now - 300, status = MsgStatus.SENT),
        ).forEach { m.store.put(it) }
        m.store.update { it.copy(readUpTo = it.readUpTo + (peer to now - 900), peerReadUpTo = it.peerReadUpTo + (peer to now)) }

        inst.context.startActivity(
            android.content.Intent(app, com.david.gitly.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(args.getString("hold")?.toLongOrNull() ?: 600_000L)
    }
}
