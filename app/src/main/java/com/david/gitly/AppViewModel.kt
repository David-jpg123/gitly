package com.david.gitly

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import com.david.gitly.chat.ChatAvailability
import com.david.gitly.chat.ChatData
import com.david.gitly.chat.ChatMsg
import com.david.gitly.chat.Messenger
import com.david.gitly.chat.RepostScope
import com.david.gitly.core.Reason
import com.david.gitly.core.ReasonKind
import com.david.gitly.chat.MsgStatus
import com.david.gitly.chat.SharedRepo
import com.david.gitly.core.Activity
import com.david.gitly.core.DeviceAuth
import com.david.gitly.core.WebAuth
import com.david.gitly.core.DeviceCode
import com.david.gitly.core.DevicePoll
import com.david.gitly.core.FeedEngine
import com.david.gitly.core.GhAsset
import com.david.gitly.core.GhOwner
import com.david.gitly.core.GhRelease
import com.david.gitly.core.GhRepo
import com.david.gitly.core.GhUser
import com.david.gitly.core.GitHub
import com.david.gitly.core.GitHubException
import com.david.gitly.core.Profile
import com.david.gitly.core.ReadmePreview
import com.david.gitly.core.Scored
import com.david.gitly.core.Signal
import com.david.gitly.core.StarredItem
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant

/** Persona a la que sigues y que te sigue. [hasGitly] null = aún comprobando. */
data class Friend(val login: String, val avatarUrl: String, val hasGitly: Boolean?)

class RepoDetails(val repo: GhRepo, val readmeHtml: String?, val releases: List<GhRelease>)

/** Texto para la UI: recurso traducible o cadena tal cual (p. ej. un error de la API). */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    var state by mutableStateOf(store.load()); private set
    val account get() = state.account
    val settings get() = state.settings
    private var gh = GitHub(account?.token)
    private val downloadClient = GitHub.defaultClient()
    private val cache = FeedCache(app)

    /** Posiciones de scroll: viven aquí para no perderse al cambiar de pestaña o abrir un repo. */
    var feedListState by mutableStateOf(LazyListState()); private set
    var exploreGridState by mutableStateOf(LazyGridState()); private set
    var profileGridState by mutableStateOf(LazyGridState()); private set

    /** Recomendaciones nuevas calculadas en segundo plano, esperando a que el usuario las pida. */
    var pendingFeed by mutableStateOf<FeedSnapshot?>(null); private set

    // Sesión
    var me by mutableStateOf<GhUser?>(null); private set
    var loggingIn by mutableStateOf(false); private set
    var loginError by mutableStateOf<UiText?>(null); private set

    // Feed
    var feed by mutableStateOf<List<Scored>>(emptyList()); private set
    var feedLoading by mutableStateOf(false); private set
    var feedError by mutableStateOf<UiText?>(null); private set
    var profile by mutableStateOf<Profile?>(null); private set
    var starred by mutableStateOf<List<StarredItem>>(emptyList()); private set
    var starredSet by mutableStateOf<Set<String>>(emptySet()); private set

    /** Vista previa del README por repo (clave en minúsculas). Vacía = sin README o sin imágenes. */
    val previews = mutableStateMapOf<String, ReadmePreview>()
    private val previewSem = Semaphore(6)
    private val previewRequested = HashSet<String>()

    // Explorar
    var gems by mutableStateOf<List<Scored>>(emptyList()); private set
    var gemsLoading by mutableStateOf(false); private set
    var exploreTitle by mutableStateOf<String?>(null); private set
    var exploreResults by mutableStateOf<List<GhRepo>>(emptyList()); private set
    var exploreLoading by mutableStateOf(false); private set

    // Amigos
    var activity by mutableStateOf<List<Activity>>(emptyList()); private set
    var activityLoading by mutableStateOf(false); private set

    /** Pila de pantallas sobre las pestañas; vive aquí para sobrevivir a recreate() al cambiar el idioma. */
    val nav = androidx.compose.runtime.mutableStateListOf<Route>()

    /** url del asset → progreso 0..1 de la descarga en curso */
    val downloads = mutableStateMapOf<String, Float>()
    var message by mutableStateOf<UiText?>(null)
    val rateRemaining get() = gh.rateRemaining

    private val impressedThisSession = HashSet<String>()
    private var saveJob: Job? = null

    private var previewSaveJob: Job? = null

    // (el arranque va en el init del final de la clase: aquí aún no existen todos los estados)

    private fun key(r: GhRepo) = r.fullName.lowercase()
    private fun say(@StringRes id: Int, vararg args: Any) { message = UiText.Res(id, args.toList()) }

    private fun update(now: Boolean = false, f: (AppState) -> AppState) {
        state = f(state)
        saveJob?.cancel()
        if (now) { store.save(state); return }
        saveJob = viewModelScope.launch(Dispatchers.IO) { delay(400); store.save(state) }
    }

    private fun updateAccount(f: (Account) -> Account) = update { s ->
        val active = s.account ?: return@update s
        s.copy(accounts = s.accounts.map { if (it.login == active.login) f(it) else it })
    }

    // ---- Ajustes y cuentas ------------------------------------------------------------------

    /** Guarda en el acto: el idioma se lee al recrear la Activity. */
    fun updateSettings(f: (Settings) -> Settings) = update(now = true) { it.copy(settings = f(it.settings)) }

    fun login(token: String) {
        val t = token.trim()
        loggingIn = true
        loginError = null
        viewModelScope.launch {
            try {
                val g = GitHub(t)
                val u = g.me()
                update(now = true) { s ->
                    val existing = s.accounts.firstOrNull { it.login == u.login }
                    val acc = existing?.copy(token = t, avatarUrl = u.avatarUrl) ?: Account(u.login, t, u.avatarUrl)
                    s.copy(accounts = s.accounts.filter { it.login != u.login } + acc, active = u.login)
                }
                switchTo(g, u)
            } catch (e: Exception) {
                loginError = if (e is GitHubException && e.code == 401) UiText.Res(R.string.login_invalid_token)
                else UiText.Raw(e.message ?: e.toString())
            } finally {
                loggingIn = false
            }
        }
    }

    fun switchAccount(login: String) {
        if (login == account?.login) return
        update(now = true) { it.copy(active = login) }
        switchTo(GitHub(account?.token), null)
    }

    /** Cierra la sesión de la cuenta activa; si hay otras, pasa a la primera. */
    fun logout() {
        val current = account?.login ?: return
        update(now = true) { s ->
            val rest = s.accounts.filter { it.login != current }
            s.copy(accounts = rest, active = rest.firstOrNull()?.login)
        }
        switchTo(GitHub(account?.token), null)
    }

    private fun switchTo(g: GitHub, user: GhUser?) {
        gh = g
        me = user
        feed = emptyList(); profile = null; starred = emptyList(); starredSet = emptySet()
        gems = emptyList(); exploreResults = emptyList(); exploreTitle = null; activity = emptyList()
        impressedThisSession.clear()
        pendingFeed = null
        messenger?.let { old -> viewModelScope.launch { old.shutdown() } }
        messenger = null; mutuals = null; chats = ChatData(); messagingEnabled = false
        feedListState = LazyListState(); exploreGridState = LazyGridState(); profileGridState = LazyGridState()
        if (account != null) bootstrap()
    }

    private fun bootstrap() {
        val acc = account ?: return
        setupMessenger(acc.login)
        // 1) Pintar al instante lo último que se calculó.
        val snap = cache.loadFeed(acc.login)
        if (snap != null) {
            feed = snap.items; profile = snap.profile; starredSet = snap.starred
        }
        // 2) Recalcular: en primer plano si no había nada; si no, en segundo plano y sin mover la cinta.
        val stale = snap == null || System.currentTimeMillis() - snap.savedAt > 15 * 60_000
        if (stale) refreshFeed(background = snap != null)
        else loadGems()
        viewModelScope.launch {
            try {
                me = gh.me()
                me?.let { u -> updateAccount { it.copy(avatarUrl = u.avatarUrl) } }
            } catch (e: GitHubException) {
                if (e.code == 401) { say(R.string.msg_token_expired); logout(); return@launch }
            } catch (_: Exception) { /* sin red: el feed mostrará el error */ }
            loadActivity()
        }
    }

    // ---- Feed --------------------------------------------------------------------------------

    /**
     * Recalcula el feed. Con [background] (al abrir con caché) no toca la cinta: deja el resultado en
     * [pendingFeed] y la UI muestra un aviso de "nuevas recomendaciones".
     */
    fun refreshFeed(background: Boolean = false) {
        val acc = account ?: return
        if (feedLoading || backgroundLoading) return
        if (background) backgroundLoading = true else feedLoading = true
        feedError = null
        viewModelScope.launch {
            try {
                val st = gh.starred(pages = 3)
                starred = st
                starredSet = st.map { key(it.repo) }.toSet()
                val fb = acc.feedback.values.map { Signal(it.repo, it.value, Instant.ofEpochMilli(it.at)) }
                val res = FeedEngine(gh).build(acc.login, st, fb, acc.impressions, acc.hidden)
                val snap = FeedSnapshot(acc.login, System.currentTimeMillis(), res.items, res.profile, starredSet)
                launch(Dispatchers.IO) { cache.saveFeed(snap) }
                profile = res.profile
                prefetch(res.items.take(4).map { it.repo })
                if (background && feed.isNotEmpty()) pendingFeed = snap
                else showFeed(snap)
                if (res.items.isEmpty()) feedError = res.errors.joinToString("\n").takeIf { it.isNotBlank() }
                    ?.let { UiText.Raw(it) } ?: UiText.Res(R.string.feed_empty)
                loadGems(force = true)
                syncReposts()
            } catch (e: Exception) {
                if (!background || feed.isEmpty()) feedError = UiText.Raw(e.message ?: e.toString())
            } finally {
                feedLoading = false
                backgroundLoading = false
            }
        }
    }

    private var backgroundLoading = false

    private suspend fun showFeed(snap: FeedSnapshot) {
        feed = snap.items
        pendingFeed = null
        impressedThisSession.clear()
        runCatching { feedListState.scrollToItem(0) }
    }

    /** El usuario toca "Nuevas recomendaciones". */
    fun applyPendingFeed() {
        val p = pendingFeed ?: return
        viewModelScope.launch { showFeed(p) }
    }

    /** Pide por adelantado las vistas previas de los próximos posts. */
    fun prefetch(repos: List<GhRepo>) = repos.forEach { r ->
        loadPreview(r)
        previews[key(r)]?.images?.let(::prefetchImages) // ya en caché: precargar también las imágenes
    }

    private val prefetchedImages = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Descarga por adelantado las dos primeras imágenes de un post al tamaño exacto en que se pintan
     * (cuadrado del ancho de pantalla), así al llegar ya están en memoria o en disco.
     */
    private fun prefetchImages(images: List<String>) {
        val app = getApplication<Application>()
        val side = app.resources.displayMetrics.widthPixels
        for (url in images.take(1).map(com.david.gitly.core.ReadmeParser::direct)) { // solo la portada: en redes lentas no robar ancho de banda a lo visible
            if (!prefetchedImages.add(url)) continue
            app.imageLoader.enqueue(
                coil.request.ImageRequest.Builder(app).data(url).size(side, side)
                    .scale(coil.size.Scale.FIT).build()
            )
        }
    }

    fun loadPreview(r: GhRepo) {
        val k = key(r)
        if (k in previews || !previewRequested.add(k)) return
        viewModelScope.launch {
            val p = previewSem.withPermit { runCatching { gh.readmePreview(r.fullName) }.getOrNull() }
            previews[k] = p ?: ReadmePreview(emptyList(), "")
            schedulePreviewSave()
            p?.images?.let(::prefetchImages)
        }
    }

    private fun schedulePreviewSave() {
        previewSaveJob?.cancel()
        previewSaveJob = viewModelScope.launch {
            delay(3000)
            val copy = previews.toMap()
            withContext(Dispatchers.IO) { cache.savePreviews(copy) }
        }
    }

    /** Una imagen del README no carga: se quita para no dejar un hueco en el carrusel. */
    fun dropImage(r: GhRepo, url: String) {
        val k = key(r)
        previews[k]?.let { p -> if (url in p.images) previews[k] = p.copy(images = p.images - url) }
    }

    /** Un repo se ha mostrado en el feed: si se repite sin interacción, baja posiciones. */
    fun impression(r: GhRepo) {
        val k = key(r)
        if (!impressedThisSession.add(k)) return
        updateAccount { a ->
            val m = a.impressions.toMutableMap()
            m[k] = (m[k] ?: 0) + 1
            a.copy(impressions = if (m.size > 3000) m.entries.drop(m.size - 3000).associate { it.toPair() } else m)
        }
    }

    fun isStarred(r: GhRepo) = key(r) in starredSet

    fun toggleStar(r: GhRepo, onlyAdd: Boolean = false) {
        val k = key(r)
        val on = k !in starredSet
        if (onlyAdd && !on) return
        starredSet = if (on) starredSet + k else starredSet - k
        viewModelScope.launch {
            try {
                gh.setStar(r.fullName, on)
                starred = if (on) listOf(StarredItem(Instant.now().toString(), r)) + starred
                else starred.filterNot { key(it.repo) == k }
            } catch (e: Exception) {
                starredSet = if (on) starredSet - k else starredSet + k
                message = if (e is GitHubException && (e.code == 403 || e.code == 404)) UiText.Res(R.string.msg_star_no_scope)
                else UiText.Raw(e.message ?: e.toString())
            }
        }
    }

    fun dislike(r: GhRepo) {
        val k = key(r)
        updateAccount { it.copy(hidden = it.hidden + k, feedback = it.feedback + (k to Feedback(r, -1.5, System.currentTimeMillis()))) }
        feed = feed.filterNot { key(it.repo) == k }
        gems = gems.filterNot { key(it.repo) == k }
        say(R.string.msg_disliked)
    }

    /** Abrir el detalle es una señal positiva débil. */
    fun opened(r: GhRepo) {
        val k = key(r)
        val acc = account ?: return
        if (acc.feedback.containsKey(k) || k in starredSet) return
        updateAccount { it.copy(feedback = it.feedback + (k to Feedback(r, 0.3, System.currentTimeMillis()))) }
    }

    fun resetDislikes() {
        updateAccount { a -> a.copy(hidden = emptySet(), feedback = a.feedback.filterValues { it.value > 0 }) }
        say(R.string.msg_dislikes_reset)
    }

    fun resetHistory() {
        updateAccount { it.copy(impressions = emptyMap(), feedback = emptyMap(), hidden = emptySet()) }
        say(R.string.msg_history_reset)
    }

    // ---- Explorar ----------------------------------------------------------------------------

    fun loadGems(force: Boolean = false) {
        val p = profile ?: return
        val acc = account ?: return
        if (gemsLoading || (gems.isNotEmpty() && !force)) return
        gemsLoading = true
        viewModelScope.launch {
            try { gems = FeedEngine(gh).gems(p, acc.hidden + starredSet) } catch (_: Exception) {}
            finally { gemsLoading = false }
        }
    }

    fun explore(title: String, query: String, sort: String = "stars") {
        exploreTitle = title
        exploreLoading = true
        viewModelScope.launch {
            try {
                exploreResults = gh.search(query, sort = sort, perPage = 40)
            } catch (e: Exception) {
                exploreResults = emptyList()
                message = UiText.Raw(e.message ?: e.toString())
            } finally {
                exploreLoading = false
            }
        }
    }

    // ---- Amigos y perfiles ------------------------------------------------------------------

    fun loadActivity() {
        val login = account?.login ?: return
        if (activityLoading) return
        activityLoading = true
        viewModelScope.launch {
            try { activity = FeedEngine(gh).activity(login) } catch (e: Exception) { message = UiText.Raw(e.message ?: e.toString()) }
            finally { activityLoading = false }
        }
    }

    suspend fun user(login: String): GhUser = if (login.equals(account?.login, true) && me != null) me!! else gh.user(login)
    suspend fun userRepos(login: String): List<GhRepo> = gh.userRepos(login)
    suspend fun userStarred(login: String): List<GhRepo> =
        if (login.equals(account?.login, true) && starred.isNotEmpty()) starred.map { it.repo } else gh.userStarred(login, perPage = 60)
    suspend fun people(login: String, followers: Boolean): List<GhOwner> =
        if (followers) gh.followers(login) else gh.following(login)
    suspend fun isFollowing(login: String): Boolean = runCatching { gh.isFollowing(login) }.getOrDefault(false)

    /** Devuelve el nuevo estado de seguimiento (o el anterior si falló). */
    suspend fun setFollow(login: String, on: Boolean): Boolean = try {
        gh.setFollow(login, on); on
    } catch (e: Exception) {
        message = if (e is GitHubException && (e.code == 403 || e.code == 404)) UiText.Res(R.string.msg_follow_no_scope)
        else UiText.Raw(e.message ?: e.toString())
        !on
    }

    /** Guarda cambios del perfil en GitHub. Devuelve true si fue bien. */
    suspend fun saveProfile(changes: Map<String, String>): Boolean {
        if (changes.isEmpty()) return true
        return try {
            me = gh.updateProfile(changes)
            say(R.string.msg_profile_saved)
            true
        } catch (e: Exception) {
            message = if (e is GitHubException && (e.code == 403 || e.code == 404 || e.code == 401)) UiText.Res(R.string.msg_profile_no_scope)
            else UiText.Raw(e.message ?: e.toString())
            false
        }
    }

    // ---- Mensajes directos ------------------------------------------------------------------

    var messenger: Messenger? = null; private set
    var chats by mutableStateOf(ChatData()); private set
    var messagingEnabled by mutableStateOf(false); private set
    var enablingMessaging by mutableStateOf(false); private set
    var mutuals by mutableStateOf<List<Friend>?>(null); private set
    private var chatSyncing = false
    private var mutualsLoading = false

    /** No leídos para el contador: los silenciados y bloqueados no cuentan. */
    val unreadTotal get() = chats.threads.keys.filter { !chats.isMuted(it) && !chats.isBlocked(it) }.sumOf { chats.unread(it) }

    private var chatsWatch: Job? = null

    private fun setupMessenger(login: String) {
        val m = getApplication<GitlyApp>().messenger(login, account?.token)
        messenger = m
        refreshChats()
        // Lo que escriban el worker o la respuesta desde la notificación también se ve aquí.
        chatsWatch?.cancel()
        chatsWatch = viewModelScope.launch { m.store.changes.collect { if (messenger === m) refreshChats() } }
        if (m.enabled) { syncChats(); loadMutuals() }
    }

    // ---- Reposts ------------------------------------------------------------------------------

    fun isReposted(r: GhRepo) = r.fullName.lowercase() in chats.myReposts

    /**
     * Reposts que [login] te deja ver: los públicos (si en su privacidad eligió "Todo el mundo") más
     * los que te envió en privado por ser amigos mutuos. null = no usa Gitly / no comparte nada contigo.
     */
    suspend fun userReposts(login: String): List<com.david.gitly.chat.RepostItem>? {
        val m = messenger ?: return null
        if (login.equals(account?.login, true)) return chats.myReposts.values.sortedByDescending { it.ts }
        val public = runCatching { m.fetchUserReposts(login) }.getOrNull()
        val private = chats.reposts.values.filter { it.from.equals(login, true) }
        if (public == null && private.isEmpty()) return null
        return (public.orEmpty() + private).groupBy { it.repo.fullName.lowercase() }
            .map { (_, v) -> v.maxBy { it.ts } }.sortedByDescending { it.ts }
    }

    /** Reposts públicos de los amigos mutuos que usan Gitly (los privados llegan con los mensajes). */
    fun syncReposts() {
        val m = messenger ?: return
        if (!m.enabled) return
        val friends = mutuals?.filter { it.hasGitly == true } ?: return
        viewModelScope.launch {
            val keys = friends.mapNotNull { f -> m.resolve(f.login)?.let { f.login to it } }.toMap()
            runCatching { m.syncPublicReposts(keys) }
            refreshChats()
        }
    }

    /** Repostea o quita el repost, según el ajuste "Quién ve mis reposts". */
    fun toggleRepost(r: GhRepo) {
        val m = messenger ?: return
        if (!m.enabled) { say(R.string.msg_repost_needs_messages); return }
        val undo = isReposted(r)
        val scope = when (settings.privacy.repostsVisibleTo) {
            Audience.EVERYONE -> RepostScope.PUBLIC
            Audience.FOLLOWING -> RepostScope.FRIENDS
            Audience.NOBODY -> RepostScope.ONLY_ME
        }
        viewModelScope.launch {
            val friends = mutuals?.filter { it.hasGitly == true && !chats.isBlocked(it.login) }?.map { it.login }.orEmpty()
            // Marcar en el acto; el envío a los relays sigue en segundo plano.
            val poll = launch { while (true) { refreshChats(); delay(300) } }
            val sent = runCatching { m.repost(SharedRepo(r.fullName, r.description, r.stars, r.language), scope, friends, undo) }
            poll.cancel()
            refreshChats()
            when {
                sent.isFailure -> say(R.string.msg_send_failed)
                undo -> say(R.string.msg_unreposted)
                else -> say(R.string.msg_reposted)
            }
        }
    }

    /**
     * Feed que se muestra: el calculado más los reposts de amigos de las dos últimas semanas,
     * intercalados (posiciones 1, 4, 7…) con la marca de quién lo reposteó.
     */
    val displayFeed: List<Scored>
        get() {
            val me = account?.login?.lowercase()
            val hidden = account?.hidden.orEmpty()
            val since = System.currentTimeMillis() / 1000 - 14 * 86_400
            val byRepo = chats.reposts.values
                .filter {
                    it.from != me && it.ts >= since && it.repo.fullName.lowercase() !in hidden &&
                        !chats.isBlocked(it.from) && it.from !in chats.repostsMuted
                }
                .groupBy { it.repo.fullName.lowercase() }
            if (byRepo.isEmpty()) return feed
            val base = feed.associateBy { it.repo.fullName.lowercase() }
            val reposted = byRepo.entries.sortedByDescending { e -> e.value.maxOf { it.ts } }.map { (k, items) ->
                val froms = items.sortedByDescending { it.ts }.map { it.from }
                val repo = base[k]?.repo ?: items.first().repo.let { sr ->
                    val owner = sr.fullName.substringBefore('/')
                    GhRepo(-(sr.fullName.lowercase().hashCode().toLong() and 0xffffffffL) - 1, sr.fullName, sr.fullName.substringAfter('/'),
                        GhOwner(owner, "https://github.com/$owner.png"), sr.description, sr.language, stars = sr.stars)
                }
                Scored(repo, 1.0, Reason(ReasonKind.REPOST, froms.take(2), (froms.size - 2).coerceAtLeast(0)), froms)
            }
            val rest = feed.filter { it.repo.fullName.lowercase() !in byRepo }.toMutableList()
            val out = ArrayList<Scored>(rest.size + reposted.size)
            var ri = 0
            var i = 0
            while (i < rest.size || ri < reposted.size) {
                if (ri < reposted.size && (out.size % 3 == 1 || i >= rest.size)) out += reposted[ri++]
                else if (i < rest.size) out += rest[i++]
            }
            return out
        }

    private fun refreshChats() {
        val m = messenger ?: return
        chats = m.store.data
        messagingEnabled = m.enabled
    }

    fun enableMessaging() {
        val m = messenger ?: return
        if (enablingMessaging) return
        enablingMessaging = true
        viewModelScope.launch {
            try {
                m.enable()
                refreshChats()
                say(R.string.msg_messaging_on)
                loadMutuals(force = true)
                syncChats()
            } catch (e: Exception) {
                message = if (e is GitHubException && e.code in setOf(401, 403, 404, 422)) UiText.Res(R.string.msg_gist_no_scope)
                else UiText.Raw(e.message ?: e.toString())
            } finally {
                enablingMessaging = false
            }
        }
    }

    /** Amigos mutuos (sigues y te siguen) y si cada uno usa Gitly. */
    fun loadMutuals(force: Boolean = false) {
        val m = messenger ?: return
        val me = account?.login ?: return
        if (mutualsLoading || (mutuals != null && !force)) return
        mutualsLoading = true
        viewModelScope.launch {
            try {
                val following = async { gh.following(me) }
                val followers = async { gh.followers(me).map { it.login.lowercase() }.toSet() }
                val mutual = following.await().filter { it.login.lowercase() in followers.await() }
                m.markMutual(mutual.map { it.login })
                var list = mutual.map { Friend(it.login, it.avatarUrl, null) }
                mutuals = list
                val sem = Semaphore(6)
                mutual.map { u -> async { sem.withPermit { u.login to (m.resolve(u.login) != null) } } }
                    .forEach { d ->
                        val (login, has) = d.await()
                        list = list.map { if (it.login == login) it.copy(hasGitly = has) else it }
                        mutuals = list.sortedBy { if (it.hasGitly == true) 0 else 1 }
                    }
                syncReposts()
                // El worker necesita la lista sin UI, y a los amigos nuevos hay que contarles el timbre.
                m.rememberFriends(list.filter { it.hasGitly == true }.map { it.login })
                launch { runCatching { m.announcePush(com.david.gitly.push.Push.url(getApplication())) } }
            } catch (e: Exception) {
                if (mutuals == null) mutuals = emptyList()
                message = UiText.Raw(e.message ?: e.toString())
            } finally {
                mutualsLoading = false
            }
        }
    }

    fun syncChats() {
        val m = messenger ?: return
        if (!m.enabled || chatSyncing) return
        chatSyncing = true
        viewModelScope.launch {
            try {
                m.sync { settings.privacy.messagesFrom != Audience.NOBODY }
            } catch (_: Exception) {
            } finally {
                chatSyncing = false
                refreshChats()
            }
        }
    }

    suspend fun chatAvailability(login: String): ChatAvailability =
        messenger?.availability(login) ?: ChatAvailability.DISABLED

    fun sendMessage(to: String, text: String, repo: SharedRepo? = null) {
        val m = messenger ?: return
        viewModelScope.launch {
            // Pintar ya el mensaje "enviando" mientras sale hacia los relays.
            val poll = launch { while (true) { refreshChats(); delay(300) } }
            val r = runCatching { m.send(to, text, repo) }
            poll.cancel()
            refreshChats()
            if (r.isFailure || r.getOrNull()?.status == MsgStatus.FAILED) say(R.string.msg_send_failed)
            else if (repo != null) say(R.string.msg_sent_to, to)
        }
    }

    fun sendRepo(to: String, r: GhRepo) =
        sendMessage(to, "", SharedRepo(r.fullName, r.description, r.stars, r.language))

    fun retryMessage(msg: ChatMsg) {
        val m = messenger ?: return
        viewModelScope.launch {
            runCatching { m.retry(msg) }
            refreshChats()
        }
    }

    fun markRead(peer: String) {
        val m = messenger ?: return
        m.markRead(peer, settings.privacy.readReceipts)
        account?.login?.let { com.david.gitly.push.Notifications.cancel(getApplication(), it, peer) }
        refreshChats()
    }

    /** Tocar una notificación: pasar a esa cuenta si hace falta y abrir la conversación. */
    fun openChatFromNotification(accountLogin: String, peer: String) {
        if (!accountLogin.equals(account?.login, true) && state.accounts.any { it.login.equals(accountLogin, true) }) {
            switchAccount(state.accounts.first { it.login.equals(accountLogin, true) }.login)
        }
        if ((nav.lastOrNull() as? Route.Chat)?.login?.equals(peer, true) != true) nav.add(Route.Chat(peer))
    }

    /** Pide (o renueva) la URL del timbre si hay distribuidor. Desde la actividad, que es quien puede. */
    var pushStatus by mutableStateOf(0); private set
    fun refreshPushStatus() { pushStatus++ }

    // ---- Bloquear / silenciar ---------------------------------------------------------------

    /** Bloquea en Gitly y, si [onGitHub], también en GitHub (deja de seguirte y no puede interactuar). */
    fun block(login: String, onGitHub: Boolean) {
        val m = messenger ?: return
        m.setBlocked(login, true)
        account?.login?.let { com.david.gitly.push.Notifications.cancel(getApplication(), it, login.lowercase()) }
        refreshChats()
        say(R.string.msg_blocked, login)
        if (onGitHub) viewModelScope.launch {
            runCatching { gh.setBlock(login, true) }.onFailure { e ->
                message = if (e is GitHubException && e.code in setOf(401, 403, 404)) UiText.Res(R.string.msg_block_no_scope)
                else UiText.Raw(e.message ?: e.toString())
            }
            loadMutuals(force = true)
        }
    }

    /** Desbloquea en Gitly y también en GitHub (si no estaba bloqueado allí, GitHub responde 404 y se ignora). */
    fun unblock(login: String) {
        val m = messenger ?: return
        m.setBlocked(login, false)
        refreshChats()
        say(R.string.msg_unblocked, login)
        viewModelScope.launch { runCatching { gh.setBlock(login, false) } }
    }

    fun isBlocked(login: String) = chats.isBlocked(login)
    fun isMuted(login: String) = chats.isMuted(login)
    fun repostsMuted(login: String) = login.lowercase() in chats.repostsMuted

    /** Silencia los avisos de [login] durante [hours] (null = siempre) o quita el silencio con [off]. */
    fun mute(login: String, hours: Int?, off: Boolean = false) {
        val m = messenger ?: return
        m.setMuted(login, when {
            off -> null
            hours == null -> Long.MAX_VALUE
            else -> System.currentTimeMillis() + hours * 3_600_000L
        })
        if (!off) account?.login?.let { com.david.gitly.push.Notifications.cancel(getApplication(), it, login.lowercase()) }
        refreshChats()
        say(if (off) R.string.msg_unmuted else R.string.msg_muted, login)
    }

    fun setRepostsMuted(login: String, on: Boolean) {
        messenger?.setRepostsMuted(login, on)
        refreshChats()
        say(if (on) R.string.msg_reposts_muted else R.string.msg_reposts_unmuted, login)
    }

    fun exportMessagingKey(): String? = messenger?.exportSecret()

    fun importMessagingKey(secret: String) {
        val m = messenger ?: return
        viewModelScope.launch {
            val ok = runCatching { m.importSecret(secret) }.getOrDefault(false)
            refreshChats()
            say(if (ok) R.string.msg_key_imported else R.string.msg_key_invalid)
            if (ok) syncChats()
        }
    }

    // ---- Inicio de sesión con GitHub (device flow) ------------------------------------------

    val oauthAvailable get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()
    var deviceCode by mutableStateOf<DeviceCode?>(null); private set
    var deviceError by mutableStateOf<UiText?>(null); private set
    private var deviceJob: Job? = null

    fun startDeviceLogin() {
        deviceJob?.cancel()
        deviceError = null
        deviceJob = viewModelScope.launch {
            try {
                val auth = DeviceAuth(BuildConfig.GITHUB_CLIENT_ID)
                // "user" incluye leer y editar el perfil y seguir a gente; "public_repo" permite dar estrellas.
                val code = auth.start("public_repo user gist")
                deviceCode = code
                var interval = code.interval
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    when (val r = auth.poll(code)) {
                        is DevicePoll.Token -> { deviceCode = null; login(r.token); return@launch }
                        is DevicePoll.Pending -> r.interval?.let { interval = it }
                        is DevicePoll.Failed -> {
                            deviceCode = null
                            deviceError = if (r.reason == "access_denied") UiText.Res(R.string.login_denied) else UiText.Raw(r.reason)
                            return@launch
                        }
                    }
                }
                deviceCode = null
                deviceError = UiText.Res(R.string.login_expired)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                deviceCode = null
                deviceError = if (e is GitHubException && e.code == 404) UiText.Res(R.string.login_oauth_missing)
                else UiText.Raw(e.message ?: e.toString())
            }
        }
    }

    // ---- Inicio de sesión web (la página de login de GitHub dentro de la app) -------------------

    private val webRedirect = "gitly://oauth/callback"
    private val pendingFile get() = java.io.File(getApplication<Application>().filesDir, "oauth_pending")
    private fun webAuth() = WebAuth(BuildConfig.GITHUB_CLIENT_ID, BuildConfig.GITHUB_CLIENT_SECRET)

    fun startWebLogin(ctx: Context) {
        val auth = webAuth()
        val p = auth.newPending()
        // A disco: si Android cierra Gitly mientras estás en GitHub, al volver sigue sabiendo qué esperaba.
        pendingFile.writeText("${p.state}\n${p.verifier}")
        loginError = null
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(ctx, android.net.Uri.parse(auth.authorizeUrl(p, webRedirect, "public_repo user gist")))
    }

    fun completeWebLogin(uri: android.net.Uri) {
        val lines = runCatching { pendingFile.readLines() }.getOrNull()
        pendingFile.delete()
        val code = uri.getQueryParameter("code")
        if (uri.getQueryParameter("error") != null) { loginError = UiText.Res(R.string.login_denied); return }
        if (lines == null || lines.size < 2 || code == null || uri.getQueryParameter("state") != lines[0]) {
            loginError = UiText.Res(R.string.login_web_state); return
        }
        loggingIn = true
        viewModelScope.launch {
            try {
                val token = webAuth().exchange(code, WebAuth.Pending(lines[0], lines[1]), webRedirect)
                login(token)
            } catch (e: Exception) {
                loginError = UiText.Res(R.string.login_web_failed, listOf(e.message ?: e.toString()))
                loggingIn = false
            }
        }
    }

    fun cancelDeviceLogin() {
        deviceJob?.cancel()
        deviceCode = null
    }

    // ---- Detalle e instalación --------------------------------------------------------------

    suspend fun details(fullName: String): RepoDetails = coroutineScope {
        val repo = async { gh.repo(fullName) }
        val readme = async { runCatching { gh.readmeHtml(fullName) }.getOrNull() }
        val releases = async { runCatching { gh.releases(fullName) }.getOrDefault(emptyList()) }
        val isStarred = async { runCatching { gh.isStarred(fullName) }.getOrNull() }
        val r = repo.await()
        isStarred.await()?.let { s -> starredSet = if (s) starredSet + key(r) else starredSet - key(r) }
        RepoDetails(r, readme.await(), releases.await())
    }

    /** [uiContext] es la Activity: así, al volver de Ajustes se regresa a Gitly. */
    fun installApk(uiContext: Context, asset: GhAsset) {
        val ctx = getApplication<Application>()
        if (!ApkInstaller.canInstall(ctx)) {
            ApkInstaller.requestPermission(uiContext)
            say(R.string.msg_allow_install)
            return
        }
        if (asset.url in downloads) return
        downloads[asset.url] = 0f
        viewModelScope.launch {
            try {
                val file = ApkInstaller.download(ctx, downloadClient, asset) { p ->
                    viewModelScope.launch(Dispatchers.Main) { if (asset.url in downloads) downloads[asset.url] = p }
                }
                withContext(Dispatchers.Main) { ApkInstaller.install(ctx, file) }
            } catch (e: Exception) {
                say(R.string.msg_download_failed, e.message ?: "")
            } finally {
                downloads.remove(asset.url)
            }
        }
    }

    /**
     * Arranque. Tiene que ser lo último de la clase: Kotlin inicializa en orden de declaración y
     * bootstrap() toca estados declarados más arriba (feed, chats…); antes de ellos daba NPE.
     */
    init {
        previews.putAll(cache.loadPreviews())
        if (account != null) bootstrap()
    }
}
