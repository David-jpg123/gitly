package com.david.gitly

import android.content.Context
import com.david.gitly.core.GhRepo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Señal explícita del usuario sobre un repo (positiva o negativa). */
@Serializable
data class Feedback(val repo: GhRepo, val value: Double, val at: Long)

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Quién puede hacer algo (mensajes, ver reposts). */
@Serializable enum class Audience { EVERYONE, FOLLOWING, NOBODY }

/** Ajustes de privacidad: se guardan ya, se aplicarán cuando existan DMs y reposts. */
@Serializable
data class Privacy(
    val messagesFrom: Audience = Audience.FOLLOWING,
    val repostsVisibleTo: Audience = Audience.EVERYONE,
    val readReceipts: Boolean = true,
    val shareActivity: Boolean = true,
)

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** "" = idioma del sistema; si no, etiqueta BCP-47 ("es", "en"). */
    val language: String = "",
    val privacy: Privacy = Privacy(),
    /** notificar mensajes nuevos con la app cerrada */
    val notifyMessages: Boolean = true,
)

/** Una cuenta de GitHub con su propio historial para el algoritmo. */
@Serializable
data class Account(
    val login: String,
    val token: String,
    val avatarUrl: String = "",
    /** clave: full_name en minúsculas */
    val feedback: Map<String, Feedback> = emptyMap(),
    /** veces que un repo se ha mostrado en el feed sin interacción */
    val impressions: Map<String, Int> = emptyMap(),
    /** repos marcados como "no me interesa" */
    val hidden: Set<String> = emptySet(),
)

@Serializable
data class AppState(
    val settings: Settings = Settings(),
    val accounts: List<Account> = emptyList(),
    val active: String? = null,
) {
    val account: Account? get() = accounts.firstOrNull { it.login == active } ?: accounts.firstOrNull()
}

/** Persistencia local mínima: un JSON en el almacenamiento privado de la app. */
class Store(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppState = runCatching { json.decodeFromString<AppState>(file.readText()) }.getOrDefault(AppState())

    @Synchronized
    fun save(state: AppState) {
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(json.encodeToString(AppState.serializer(), state))
        tmp.renameTo(file)
    }
}
