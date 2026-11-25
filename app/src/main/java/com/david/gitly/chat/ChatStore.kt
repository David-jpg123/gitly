package com.david.gitly.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable enum class MsgStatus { SENDING, SENT, FAILED }

/** Repo compartido dentro de un mensaje (se muestra como tarjeta). */
@Serializable
data class SharedRepo(
    val fullName: String,
    val description: String? = null,
    val stars: Int = 0,
    val language: String? = null,
)

@Serializable
data class ChatMsg(
    val id: String,
    /** login de GitHub del otro, en minúsculas */
    val peer: String,
    val mine: Boolean,
    val text: String,
    /** segundos epoch */
    val ts: Long,
    val repo: SharedRepo? = null,
    val status: MsgStatus = MsgStatus.SENT,
)

/** Un repost recibido de un amigo. */
@Serializable
data class RepostItem(
    val from: String,
    val repo: SharedRepo,
    /** segundos epoch */
    val ts: Long,
)

@Serializable
data class ChatData(
    val threads: Map<String, List<ChatMsg>> = emptyMap(),
    /** última sincronización con los relays (segundos epoch) */
    val lastSync: Long = 0,
    /** hasta qué ts ha leído el usuario cada conversación */
    val readUpTo: Map<String, Long> = emptyMap(),
    /** ids de rumor ya procesados (para no duplicar) */
    val seen: List<String> = emptyList(),
    /** reposts recibidos, clave "login|owner/repo" (minúsculas) */
    val reposts: Map<String, RepostItem> = emptyMap(),
    /** mis reposts: owner/repo (minúsculas) → repo + ts */
    val myReposts: Map<String, RepostItem> = emptyMap(),
    /** timbre (endpoint UnifiedPush) de cada amigo, recibido cifrado: login → URL */
    val peerPush: Map<String, String> = emptyMap(),
    /** a quién le he contado ya mi timbre actual: login → URL enviada ("" = retirado) */
    val pushSentTo: Map<String, String> = emptyMap(),
    /** amigos mutuos que usan Gitly (última lista conocida, para trabajar sin la UI) */
    val friends: List<String> = emptyList(),
    /** bloqueados: no se aceptan sus mensajes, reposts ni timbre, y no se les puede escribir */
    val blocked: List<String> = emptyList(),
    /** silenciados: login → hasta cuándo (ms epoch; Long.MAX_VALUE = siempre). Llegan, pero sin avisar. */
    val mutedUntil: Map<String, Long> = emptyMap(),
    /** amigos cuyos reposts no quiero ver en el feed */
    val repostsMuted: List<String> = emptyList(),
    /** confirmaciones de lectura recibidas: login → hasta qué ts ha leído mis mensajes */
    val peerReadUpTo: Map<String, Long> = emptyMap(),
    /** última confirmación de lectura que envié a cada uno (para no repetir) */
    val receiptSent: Map<String, Long> = emptyMap(),
) {
    fun isBlocked(peer: String) = peer.lowercase() in blocked
    fun isMuted(peer: String, now: Long = System.currentTimeMillis()) = (mutedUntil[peer.lowercase()] ?: 0) > now

    fun unread(peer: String): Int {
        val r = readUpTo[peer] ?: 0
        return threads[peer].orEmpty().count { !it.mine && it.ts > r }
    }
}

/** Conversaciones de una cuenta, en un JSON del almacenamiento privado. */
class ChatStore(context: Context, login: String) {
    private val file = File(context.filesDir, "chats-${login.lowercase()}.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var data: ChatData = runCatching { json.decodeFromString<ChatData>(file.readText()) }.getOrDefault(ChatData())
        private set

    private val _changes = MutableStateFlow(0L)
    /** Cambia cada vez que se guarda algo (la UI se refresca aunque escriba un worker). */
    val changes: StateFlow<Long> = _changes

    @Synchronized
    fun update(f: (ChatData) -> ChatData): ChatData {
        val next = f(data)
        if (next == data) return data
        data = next
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ChatData.serializer(), data))
        tmp.renameTo(file)
        _changes.value = _changes.value + 1
        return data
    }

    /** Añade o sustituye (mismo id) un mensaje en su conversación, en orden temporal. */
    fun put(m: ChatMsg) = update { d ->
        val list = d.threads[m.peer].orEmpty().filter { it.id != m.id } + m
        d.copy(threads = d.threads + (m.peer to list.sortedBy { it.ts }))
    }

    fun delete() { file.delete() }
}
