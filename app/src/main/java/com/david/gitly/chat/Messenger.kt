package com.david.gitly.chat

import android.content.Context
import android.util.Log
import com.david.gitly.core.GitHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nostrdevkit.sdk.Client
import org.nostrdevkit.sdk.ClientBuilder
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.Filter
import org.nostrdevkit.sdk.Keys
import org.nostrdevkit.sdk.Kind
import org.nostrdevkit.sdk.KindStandard
import org.nostrdevkit.sdk.NostrSigner
import org.nostrdevkit.sdk.PublicKey
import org.nostrdevkit.sdk.RelayStatus
import org.nostrdevkit.sdk.RelayUrl
import org.nostrdevkit.sdk.ReqTarget
import org.nostrdevkit.sdk.SendEventTarget
import org.nostrdevkit.sdk.Tag
import org.nostrdevkit.sdk.Timestamp
import org.nostrdevkit.sdk.UnsignedEvent
import org.nostrdevkit.sdk.UnwrappedGift
import org.nostrdevkit.sdk.nip59MakeGiftWrap
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GitlyChat"
private const val DM_KIND: UShort = 14u
/** Timbre: el contenido es la URL UnifiedPush de quien lo envía ("" = la retira). Solo viaja cifrado. */
private const val PUSH_KIND: UShort = 20716u
/** Confirmación de lectura: etiqueta ["read", ts] = he leído hasta ese mensaje. Solo viaja cifrada. */
private const val RECEIPT_KIND: UShort = 20717u
/** Repost de Gitly: evento reemplazable (uno por repo y autor, "d" = owner/repo). */
private const val REPOST_KIND: UShort = 31616u

/** A quién llega un repost. */
enum class RepostScope { PUBLIC, FRIENDS, ONLY_ME }
const val BINDING_FILE = "gitly.json"

val RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.0xchat.com")

/** Contenido del gist público que une un login de GitHub con su clave de mensajería. */
@Serializable
data class Binding(val app: String = "gitly", val v: Int = 1, val login: String, val pubkey: String)

/** Firmante local para que rust-nostr cifre (NIP-44) y firme con nuestras claves. */
private class KeysSigner(val keys: Keys) : NostrSigner {
    override fun getPublicKey(): PublicKey = keys.publicKey()
    override fun signEvent(unsignedEvent: UnsignedEvent): Event = keys.signEvent(unsignedEvent)
    override fun nip04Encrypt(publicKey: PublicKey, content: String): String = keys.nip04Encrypt(publicKey, content)
    override fun nip04Decrypt(publicKey: PublicKey, encryptedContent: String): String = keys.nip04Decrypt(publicKey, encryptedContent)
    override fun nip44Encrypt(publicKey: PublicKey, content: String): String = keys.nip44Encrypt(publicKey, content)
    override fun nip44Decrypt(publicKey: PublicKey, payload: String): String = keys.nip44Decrypt(publicKey, payload)
}

/** Si alguien puede escribir a [peer], y si no, por qué. */
enum class ChatAvailability { OK, CHECKING, NOT_MUTUAL, NO_GITLY, DISABLED, BLOCKED }

/**
 * Mensajes directos cifrados de extremo a extremo sobre Nostr (NIP-17: sello + sobre NIP-59).
 *
 * - Identidad: cada cuenta de GitHub tiene una clave Nostr; su parte pública se publica en un gist
 *   `gitly.json` del propio usuario. Solo él puede crear gists en su cuenta, así que el gist prueba
 *   que esa clave es suya.
 * - Regla: solo se escribe a (y se acepta de) quien os seguís mutuamente en GitHub. Los relays no
 *   saben nada de GitHub, así que la comprobación la hace cada app al enviar y al recibir.
 */
class Messenger(context: Context, val login: String, private val gh: GitHub) {
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val box = SecretBox(context, "msgkey-${login.lowercase()}")
    val store = ChatStore(context, login)
    private val json = Json { ignoreUnknownKeys = true }

    private var keys: Keys? = box.load()?.let { runCatching { Keys.parse(it) }.getOrNull() }
    val enabled get() = keys != null
    val pubkeyHex: String? get() = keys?.publicKey()?.toHex()

    /** Solo para pruebas: sustituyen la consulta a GitHub (gists y seguidores). */
    @androidx.annotation.VisibleForTesting internal var resolveOverride: (suspend (String) -> String?)? = null
    @androidx.annotation.VisibleForTesting internal var mutualOverride: (suspend (String) -> Boolean)? = null

    /** Solo para pruebas: clave local sin publicar gist. */
    @androidx.annotation.VisibleForTesting internal fun useTestKey() {
        keys = Keys.generate().also { box.save(it.secretKey().toHex()) }
    }

    // Cachés en memoria: login → clave (o null = no usa Gitly) y login → ¿mutuo?
    private class Cached<T>(val value: T, val at: Long)
    private val bindings = ConcurrentHashMap<String, Cached<String?>>()
    private val mutual = ConcurrentHashMap<String, Cached<Boolean>>()

    // ---- Identidad ----------------------------------------------------------------------------

    /** Crea la clave (si no hay) y publica el gist. Lanza GitHubException si falta el permiso `gist`. */
    suspend fun enable() = withContext(Dispatchers.IO) {
        val k = keys ?: Keys.generate().also { box.save(it.secretKey().toHex()); keys = it }
        publishBinding(k.publicKey().toHex())
    }

    /** Clave secreta en formato nsec, para copiarla a otro móvil. */
    fun exportSecret(): String? = keys?.secretKey()?.toBech32()

    suspend fun importSecret(secret: String): Boolean = withContext(Dispatchers.IO) {
        val k = runCatching { Keys.parse(secret.trim()) }.getOrNull() ?: return@withContext false
        box.save(k.secretKey().toHex())
        keys = k
        publishBinding(k.publicKey().toHex())
        true
    }

    private suspend fun publishBinding(pub: String) {
        val content = json.encodeToString(Binding.serializer(), Binding(login = login, pubkey = pub))
        val existing = gh.userGists(login).firstOrNull { BINDING_FILE in it.files }
        if (existing != null) gh.updateGist(existing.id, BINDING_FILE, content)
        else gh.createGist("Gitly: clave pública para mensajes cifrados", BINDING_FILE, content)
        bindings[login.lowercase()] = Cached(pub, System.currentTimeMillis())
    }

    /** Clave de mensajería de [user] según su gist, o null si no usa Gitly. Caché de 6 h. */
    suspend fun resolve(user: String, fresh: Boolean = false): String? {
        resolveOverride?.let { return it(user) }
        val k = user.lowercase()
        bindings[k]?.let { if (!fresh && System.currentTimeMillis() - it.at < 6 * 3_600_000) return it.value }
        val pub = runCatching {
            val gist = gh.userGists(user).firstOrNull { BINDING_FILE in it.files } ?: return@runCatching null
            val raw = gist.files.getValue(BINDING_FILE).rawUrl
            val b = json.decodeFromString(Binding.serializer(), gh.fetchText(raw))
            b.pubkey.takeIf { b.app == "gitly" && b.login.equals(user, true) && it.length == 64 }
        }.getOrElse { e -> Log.w(TAG, "resolve $user: ${e.message}"); return bindings[k]?.value }
        bindings[k] = Cached(pub, System.currentTimeMillis())
        return pub
    }

    /** ¿Os seguís los dos? Caché de 10 min. */
    suspend fun isMutual(user: String): Boolean {
        mutualOverride?.let { return it(user) }
        val k = user.lowercase()
        mutual[k]?.let { if (System.currentTimeMillis() - it.at < 600_000) return it.value }
        val v = runCatching { gh.follows(login, user) && gh.follows(user, login) }.getOrDefault(false)
        mutual[k] = Cached(v, System.currentTimeMillis())
        return v
    }

    /** Siembra la caché de mutuos cuando ya se han calculado por otra vía (lista de amigos). */
    fun markMutual(users: Collection<String>) {
        val now = System.currentTimeMillis()
        users.forEach { mutual[it.lowercase()] = Cached(true, now) }
    }

    suspend fun availability(user: String): ChatAvailability = when {
        !enabled -> ChatAvailability.DISABLED
        store.data.isBlocked(user) -> ChatAvailability.BLOCKED
        !isMutual(user) -> ChatAvailability.NOT_MUTUAL
        resolve(user) == null -> ChatAvailability.NO_GITLY
        else -> ChatAvailability.OK
    }

    // ---- Relays -------------------------------------------------------------------------------

    private val clientLock = Mutex()
    private var client: Client? = null
    private var lastUse = 0L

    /** Cliente reutilizado; si lleva un rato sin uso se recrea (Android mata los websockets en segundo plano). */
    private suspend fun client(): Client = clientLock.withLock {
        val now = System.currentTimeMillis()
        client?.let { if (now - lastUse < 120_000) { lastUse = now; return it }; runCatching { it.shutdown() } }
        val c = ClientBuilder().build()
        RELAYS.forEach { url -> runCatching { c.addRelay(RelayUrl.parse(url)) } }
        c.connect(Duration.ofSeconds(5))
        client = c
        lastUse = now
        c
    }

    private suspend fun connected(c: Client): List<RelayUrl> = runCatching {
        c.relays().filterValues { it.status() == RelayStatus.CONNECTED }.keys.toList()
    }.getOrDefault(emptyList())

    private suspend fun awaitRelay(c: Client, maxMs: Long = 8_000) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < maxMs) {
            if (connected(c).isNotEmpty()) return
            delay(250)
        }
    }

    suspend fun shutdown() = clientLock.withLock { client?.let { runCatching { it.shutdown() } }; client = null }

    // ---- Enviar -------------------------------------------------------------------------------

    suspend fun send(to: String, text: String, repo: SharedRepo? = null): ChatMsg = withContext(Dispatchers.IO) {
        val k = keys ?: error("mensajes desactivados")
        val peer = to.lowercase()
        check(!store.data.isBlocked(peer)) { "$to está bloqueado" }
        val pub = resolve(to) ?: error("$to no usa Gitly")
        val receiver = PublicKey.parse(pub)
        val tags = buildList {
            add(Tag.publicKey(receiver))
            add(Tag.parse(listOf("gitly", login)))
            if (repo != null) add(Tag.parse(listOf("gitly-repo", repo.fullName, repo.description.orEmpty(), "${repo.stars}", repo.language.orEmpty())))
        }
        val rumor = EventBuilder(Kind(DM_KIND), text).tags(tags).finalizeUnsigned(k.publicKey()).ensureId()
        val msg = ChatMsg(rumor.id()!!.toHex(), peer, true, text, rumor.createdAt().asSecs().toLong(), repo, MsgStatus.SENDING)
        store.put(msg)
        val ok = runCatching { publish(k, receiver, rumor) }.onFailure { Log.w(TAG, "send: ${it.message}") }.getOrDefault(false)
        val done = msg.copy(status = if (ok) MsgStatus.SENT else MsgStatus.FAILED)
        store.put(done)
        // El timbre es un extra: va aparte para que su fallo nunca convierta el envío en fallido.
        if (ok) bg.launch { wake(peer) }
        done
    }

    /** Reintenta un mensaje que no llegó a ningún relay (nuevo rumor, mismo contenido). */
    suspend fun retry(m: ChatMsg): ChatMsg {
        store.update { d -> d.copy(threads = d.threads + (m.peer to d.threads[m.peer].orEmpty().filter { it.id != m.id })) }
        return send(m.peer, m.text, m.repo)
    }

    private suspend fun publish(k: Keys, receiver: PublicKey, rumor: UnsignedEvent): Boolean {
        val wrap = nip59MakeGiftWrap(KeysSigner(k), receiver, rumor)
        val c = client()
        awaitRelay(c)
        repeat(2) {
            val live = connected(c)
            val target = if (live.isEmpty()) SendEventTarget.broadcast() else SendEventTarget.to(live)
            val out = runCatching { c.sendEvent(wrap, target = target, okTimeout = Duration.ofSeconds(6)) }.getOrNull()
            if (out != null && out.success.isNotEmpty()) return true
            awaitRelay(c, 4_000)
        }
        return false
    }

    // ---- Timbre (notificaciones al instante) -------------------------------------------------

    /**
     * Cuenta mi timbre [url] (null = no tengo) a los amigos que aún no lo saben. Va cifrado igual que
     * un mensaje, así que solo lo conocen amigos mutuos y nadie más puede despertarme.
     */
    suspend fun announcePush(url: String?, friends: List<String> = store.data.friends): Int = withContext(Dispatchers.IO) {
        val k = keys ?: return@withContext 0
        val value = url.orEmpty()
        var ok = 0
        for (f in friends.map { it.lowercase() }.distinct()) {
            if (store.data.isBlocked(f)) continue
            val sent = store.data.pushSentTo[f]
            if (sent == value || (sent == null && value.isEmpty())) continue
            if (sendControl(k, f, PUSH_KIND, value)) {
                store.update { it.copy(pushSentTo = it.pushSentTo + (f to value)) }
                ok++
            }
        }
        ok
    }

    /** Mensaje de control cifrado (timbre, confirmación de lectura): sin timbre ni rastro en el chat. */
    private suspend fun sendControl(k: Keys, peer: String, kind: UShort, content: String, extra: List<List<String>> = emptyList()): Boolean {
        val pub = resolve(peer) ?: return false
        val receiver = PublicKey.parse(pub)
        val tags = listOf(Tag.publicKey(receiver), Tag.parse(listOf("gitly", login))) + extra.map { Tag.parse(it) }
        val rumor = EventBuilder(Kind(kind), content).tags(tags).finalizeUnsigned(k.publicKey()).ensureId()
        return runCatching { publish(k, receiver, rumor) }.getOrDefault(false)
    }

    // ---- Bloquear, silenciar, leído ----------------------------------------------------------

    /**
     * Bloquea o desbloquea a [peer]. Al bloquear se le retira el timbre (ya no puede despertarte);
     * al desbloquear se le volverá a contar en el siguiente anuncio.
     */
    fun setBlocked(peer: String, on: Boolean) {
        val p = peer.lowercase()
        store.update { d ->
            if (on) d.copy(blocked = (d.blocked + p).distinct(), mutedUntil = d.mutedUntil - p)
            else d.copy(blocked = d.blocked - p, pushSentTo = d.pushSentTo - p)
        }
        val k = keys ?: return
        if (on && !store.data.pushSentTo[p].isNullOrEmpty()) bg.launch {
            if (sendControl(k, p, PUSH_KIND, "")) store.update { it.copy(pushSentTo = it.pushSentTo + (p to "")) }
        }
    }

    /** Silencia los avisos de [peer] hasta [untilMs] (null = quitar silencio). */
    fun setMuted(peer: String, untilMs: Long?) {
        val p = peer.lowercase()
        store.update { d -> if (untilMs == null) d.copy(mutedUntil = d.mutedUntil - p) else d.copy(mutedUntil = d.mutedUntil + (p to untilMs)) }
    }

    fun setRepostsMuted(peer: String, on: Boolean) {
        val p = peer.lowercase()
        store.update { d -> d.copy(repostsMuted = if (on) (d.repostsMuted + p).distinct() else d.repostsMuted - p) }
    }

    /**
     * Marca la conversación como leída. Con [receipts], le dice a [peer] hasta dónde has leído
     * (una sola vez por mensaje nuevo; en segundo plano y sin timbre).
     */
    fun markRead(peer: String, receipts: Boolean) {
        val p = peer.lowercase()
        val thread = store.data.threads[p].orEmpty()
        val last = thread.lastOrNull()?.ts ?: return
        if ((store.data.readUpTo[p] ?: 0) < last) store.update { it.copy(readUpTo = it.readUpTo + (p to last)) }
        val lastTheirs = thread.lastOrNull { !it.mine }?.ts ?: return
        if (!receipts || store.data.isBlocked(p) || (store.data.receiptSent[p] ?: 0) >= lastTheirs) return
        val k = keys ?: return
        bg.launch {
            if (sendControl(k, p, RECEIPT_KIND, "", listOf(listOf("read", "$last")))) {
                store.update { it.copy(receiptSent = it.receiptSent + (p to maxOf(lastTheirs, it.receiptSent[p] ?: 0))) }
            }
        }
    }

    /** Recuerda la lista de amigos con Gitly (la usan el worker y el anuncio del timbre). */
    fun rememberFriends(friends: List<String>) {
        val l = friends.map { it.lowercase() }.sorted()
        store.update { if (it.friends == l) it else it.copy(friends = l) }
    }

    private val wakeHttp by lazy { OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build() }

    /** Toca el timbre de [peer] si nos lo dio: un POST vacío; su Gitly se despierta y sincroniza. */
    private fun wake(peer: String) {
        val url = store.data.peerPush[peer.lowercase()] ?: return
        runCatching {
            wakeHttp.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()).execute().close()
        }.onFailure { Log.w(TAG, "timbre de $peer: ${it.message}") }
    }

    // ---- Reposts ------------------------------------------------------------------------------

    private fun repoTag(r: SharedRepo) =
        Tag.parse(listOf("gitly-repo", r.fullName, r.description.orEmpty(), "${r.stars}", r.language.orEmpty()))

    private fun repoFromTags(tags: List<List<String>>): SharedRepo? =
        tags.firstOrNull { it.firstOrNull() == "gitly-repo" && it.size >= 2 }?.let {
            SharedRepo(it[1], it.getOrNull(2)?.ifBlank { null }, it.getOrNull(3)?.toIntOrNull() ?: 0, it.getOrNull(4)?.ifBlank { null })
        }

    /** Guarda o borra (etiqueta "deleted") un repost recibido; gana el más reciente. */
    private fun applyRepost(from: String, repo: SharedRepo, ts: Long, tags: List<List<String>>) {
        val key = "${from.lowercase()}|${repo.fullName.lowercase()}"
        val deleted = tags.any { it.firstOrNull() == "deleted" }
        store.update { d ->
            val prev = d.reposts[key]
            if (prev != null && prev.ts > ts) d
            else if (deleted) d.copy(reposts = d.reposts - key)
            else d.copy(reposts = d.reposts + (key to RepostItem(from.lowercase(), repo, ts)))
        }
    }

    /**
     * Repostea (o deshace con [undo]) un repo. PUBLIC: evento firmado que cualquiera que te siga en
     * Gitly puede leer. FRIENDS: sobre cifrado para cada amigo de [friends]. ONLY_ME: solo local.
     * Devuelve a cuántos destinos llegó (para ONLY_ME, 1).
     */
    suspend fun repost(repo: SharedRepo, scope: RepostScope, friends: List<String>, undo: Boolean = false): Int = withContext(Dispatchers.IO) {
        val k = keys ?: error("mensajes desactivados")
        val now = System.currentTimeMillis() / 1000
        val key = repo.fullName.lowercase()
        store.update { d ->
            if (undo) d.copy(myReposts = d.myReposts - key)
            else d.copy(myReposts = d.myReposts + (key to RepostItem(login.lowercase(), repo, now)))
        }
        if (scope == RepostScope.ONLY_ME) return@withContext 1
        val tags = buildList {
            add(Tag.identifier(key))
            add(Tag.parse(listOf("gitly", login)))
            add(repoTag(repo))
            if (undo) add(Tag.parse(listOf("deleted", "1")))
        }
        when (scope) {
            RepostScope.PUBLIC -> {
                val ev = EventBuilder(Kind(REPOST_KIND), "").tags(tags).finalize(KeysSigner(k))
                val c = client(); awaitRelay(c)
                val live = connected(c)
                val out = runCatching {
                    c.sendEvent(ev, target = if (live.isEmpty()) SendEventTarget.broadcast() else SendEventTarget.to(live), okTimeout = Duration.ofSeconds(6))
                }.getOrNull()
                if (out != null && out.success.isNotEmpty()) 1 else 0
            }
            else -> {
                var ok = 0
                for (f in friends) {
                    val pub = resolve(f) ?: continue
                    val receiver = PublicKey.parse(pub)
                    val rumor = EventBuilder(Kind(REPOST_KIND), "").tags(tags + Tag.publicKey(receiver))
                        .finalizeUnsigned(k.publicKey()).ensureId()
                    if (runCatching { publish(k, receiver, rumor) }.getOrDefault(false)) ok++
                }
                ok
            }
        }
    }

    /**
     * Reposts públicos actuales de cualquier usuario de Gitly (para su perfil). null si no usa Gitly.
     * Son eventos reemplazables: los relays guardan el último por repo, y los borrados llevan "deleted".
     */
    suspend fun fetchUserReposts(user: String): List<RepostItem>? = withContext(Dispatchers.IO) {
        val pub = resolve(user) ?: return@withContext null
        val c = client(); awaitRelay(c)
        if (connected(c).isEmpty()) return@withContext emptyList()
        val filter = Filter().kind(Kind(REPOST_KIND)).author(PublicKey.parse(pub)).limit(300u)
        val events = runCatching { c.fetchEvents(ReqTarget.auto(listOf(filter)), Duration.ofSeconds(10)) }.getOrNull()
            ?: return@withContext emptyList()
        val latest = HashMap<String, Pair<Long, RepostItem?>>()
        for (ev in events) {
            if (!ev.verify()) continue
            val tags = ev.tags().map { it.toVec() }
            if (tags.firstOrNull { it.firstOrNull() == "gitly" }?.getOrNull(1)?.equals(user, true) != true) continue
            val repo = repoFromTags(tags) ?: continue
            val ts = ev.createdAt().asSecs().toLong()
            val key = repo.fullName.lowercase()
            if ((latest[key]?.first ?: -1) > ts) continue
            latest[key] = ts to if (tags.any { it.firstOrNull() == "deleted" }) null else RepostItem(user.lowercase(), repo, ts)
        }
        latest.values.mapNotNull { it.second }.sortedByDescending { it.ts }
    }

    /** Reposts públicos de [friends] (login → clave) de las últimas 2 semanas. */
    suspend fun syncPublicReposts(friends: Map<String, String>): Int = withContext(Dispatchers.IO) {
        if (friends.isEmpty()) return@withContext 0
        val byKey = friends.entries.associate { (login, pub) -> pub.lowercase() to login }
        val c = client(); awaitRelay(c)
        if (connected(c).isEmpty()) return@withContext 0
        val since = System.currentTimeMillis() / 1000 - 14 * 86_400
        val filter = Filter().kind(Kind(REPOST_KIND)).authors(friends.values.map { PublicKey.parse(it) })
            .since(Timestamp.fromSecs(since.toULong()))
        val events = runCatching { c.fetchEvents(ReqTarget.auto(listOf(filter)), Duration.ofSeconds(10)) }.getOrNull()
            ?: return@withContext 0
        var n = 0
        for (ev in events) {
            if (!ev.verify()) continue
            val login = byKey[ev.author().toHex().lowercase()] ?: continue
            val tags = ev.tags().map { it.toVec() }
            // El evento debe decir que es de ese login (mismo par login/clave que su gist).
            if (tags.firstOrNull { it.firstOrNull() == "gitly" }?.getOrNull(1)?.equals(login, true) != true) continue
            val repo = repoFromTags(tags) ?: continue
            applyRepost(login, repo, ev.createdAt().asSecs().toLong(), tags)
            n++
        }
        n
    }

    // ---- Recibir ------------------------------------------------------------------------------

    /**
     * Descarga los sobres nuevos y guarda los mensajes válidos. [accept] permite a la app aplicar
     * la privacidad (p. ej. "Nadie puede escribirme"). Devuelve cuántos mensajes nuevos llegaron.
     */
    suspend fun sync(accept: (String) -> Boolean = { true }): Int = syncDetailed(accept)?.count ?: 0

    class SyncResult(val count: Int, val messages: List<ChatMsg>)

    /** Como [sync], pero dice qué mensajes nuevos llegaron; null si no se pudo hablar con ningún relay. */
    suspend fun syncDetailed(accept: (String) -> Boolean = { true }): SyncResult? = withContext(Dispatchers.IO) {
        val k = keys ?: return@withContext SyncResult(0, emptyList())
        val c = client()
        awaitRelay(c)
        if (connected(c).isEmpty()) return@withContext null
        // Los sobres llevan una fecha aleatoria de hasta 2 días atrás (NIP-59): se pide con margen.
        val since = (store.data.lastSync - 2 * 86_400).coerceAtLeast(0)
        val filter = Filter().kind(Kind.fromStd(KindStandard.GIFT_WRAP)).pubkey(k.publicKey())
            .since(Timestamp.fromSecs(since.toULong()))
        val events = runCatching { c.fetchEvents(ReqTarget.auto(listOf(filter)), Duration.ofSeconds(10)) }
            .onFailure { Log.w(TAG, "fetch: ${it.message}") }.getOrNull() ?: return@withContext null
        var fresh = 0
        val msgs = ArrayList<ChatMsg>()
        for (ev in events) if (process(k, ev, accept, msgs)) fresh++
        store.update { it.copy(lastSync = System.currentTimeMillis() / 1000) }
        SyncResult(fresh, msgs.sortedBy { it.ts })
    }

    private suspend fun process(k: Keys, wrap: Event, accept: (String) -> Boolean, out: MutableList<ChatMsg>): Boolean {
        val gift = runCatching { UnwrappedGift.fromGiftWrap(KeysSigner(k), wrap) }.getOrNull() ?: return false
        val rumor = gift.rumor().ensureId()
        val sender = gift.sender().toHex()
        if (rumor.author().toHex() != sender || sender == k.publicKey().toHex()) return false
        val kind = rumor.kind().asU16()
        if (kind != DM_KIND && kind != REPOST_KIND && kind != PUSH_KIND && kind != RECEIPT_KIND) return false
        val id = rumor.id()?.toHex() ?: return false
        if (id in store.data.seen) return false
        store.update { it.copy(seen = (it.seen + id).takeLast(2000)) }

        val tags = rumor.tags().map { it.toVec() }
        val claimed = tags.firstOrNull { it.firstOrNull() == "gitly" }?.getOrNull(1) ?: return false
        if (store.data.isBlocked(claimed)) return false
        // La clave que firma debe ser la que ese login publicó en su gist…
        if (resolve(claimed)?.equals(sender, true) != true && resolve(claimed, fresh = true)?.equals(sender, true) != true) {
            Log.w(TAG, "mensaje de $claimed con clave que no es la suya: descartado")
            return false
        }
        // …y tenéis que seguiros mutuamente.
        if (!accept(claimed) || !isMutual(claimed)) return false

        if (kind == RECEIPT_KIND) {
            val ts = tags.firstOrNull { it.firstOrNull() == "read" }?.getOrNull(1)?.toLongOrNull() ?: return false
            val peer = claimed.lowercase()
            store.update { d -> if ((d.peerReadUpTo[peer] ?: 0) >= ts) d else d.copy(peerReadUpTo = d.peerReadUpTo + (peer to ts)) }
            return false
        }
        if (kind == PUSH_KIND) {
            val url = rumor.content().trim()
            val peer = claimed.lowercase()
            store.update { d ->
                if (url.startsWith("https://")) d.copy(peerPush = d.peerPush + (peer to url))
                else d.copy(peerPush = d.peerPush - peer)
            }
            return false
        }
        val repo = repoFromTags(tags)
        if (kind == REPOST_KIND) {
            if (repo != null) applyRepost(claimed, repo, rumor.createdAt().asSecs().toLong(), tags)
            return true
        }
        val m = ChatMsg(id, claimed.lowercase(), false, rumor.content(), rumor.createdAt().asSecs().toLong(), repo)
        store.put(m)
        out += m
        return true
    }
}
