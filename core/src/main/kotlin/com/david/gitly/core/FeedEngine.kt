package com.david.gitly.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.random.Random

/** Un evento social. [kind] es el tipo de evento de GitHub (WatchEvent, ForkEvent…). */
data class Activity(
    val actor: GhOwner,
    val kind: String,
    val repoFullName: String,
    val at: String,
)

/**
 * Genera el feed "Para ti":
 *  1. SOCIAL   – lo que marcan/crean/forkean las personas que sigues (received_events).
 *  2. TOPIC    – búsquedas por tus topics más fuertes.
 *  3. TRENDING – repos recientes que están subiendo rápido (general + tus lenguajes).
 *  4. COSTAR   – filtrado colaborativo ligero: qué más marcan quienes marcaron lo mismo que tú.
 * Luego [Ranker] puntúa y diversifica. ~40 llamadas a la API por refresco.
 */
class FeedEngine(private val gh: GitHub, private val random: Random = Random.Default) {

    class Result(val items: List<Scored>, val profile: Profile, val errors: List<String>)

    suspend fun build(
        login: String,
        starred: List<StarredItem>,
        feedback: List<Signal>,
        impressions: Map<String, Int>,
        excluded: Set<String>,
        now: Instant = Instant.now(),
    ): Result = coroutineScope {
        val signals = starred.map { Signal(it.repo, 1.0, Instant.parse(it.starredAt)) } + feedback
        val profile = Profile.build(signals, now)
        val pool = HashMap<String, Candidate>()
        val errors = ArrayList<String>()
        fun add(r: GhRepo, src: Source): Candidate =
            pool.getOrPut(r.fullName.lowercase()) { Candidate(r) }.also { it.sources += src }

        val social = async { runCatching { social(login) } }
        val topic = async { runCatching { topical(profile, now) } }
        val trending = async { runCatching { trending(profile, now) } }
        val costar = async { runCatching { coStars(login, starred) } }

        social.await().onSuccess { list ->
            list.forEach { (repo, actors) -> add(repo, Source.SOCIAL).socialBy += actors }
        }.onFailure { errors += "social: ${it.message}" }
        topic.await().onSuccess { list -> list.forEach { add(it, Source.TOPIC) } }
            .onFailure { errors += "topics: ${it.message}" }
        trending.await().onSuccess { list -> list.forEach { add(it, Source.TRENDING) } }
            .onFailure { errors += "tendencias: ${it.message}" }
        costar.await().onSuccess { list ->
            list.forEach { cs ->
                val c = add(cs.repo, Source.COSTAR)
                c.coStars += cs.count
                if (c.coStarVia == null) c.coStarVia = cs.via
            }
        }.onFailure { errors += "co-estrellas: ${it.message}" }

        val exclude = excluded + starred.map { it.repo.fullName.lowercase() } +
            pool.keys.filter { it.startsWith("${login.lowercase()}/") }
        Result(Ranker.rank(pool.values, profile, exclude, impressions, now), profile, errors)
    }

    // ---- 1. Social ---------------------------------------------------------------------------

    private suspend fun social(login: String): List<Pair<GhRepo, Set<String>>> = coroutineScope {
        val byRepo = LinkedHashMap<String, MutableSet<String>>()
        for (e in gh.receivedEvents(login)) {
            if (e.actor.login.equals(login, true)) continue
            if (activityKind(e) == null) continue
            byRepo.getOrPut(e.repo.name) { LinkedHashSet() } += e.actor.login
        }
        val top = byRepo.entries.sortedByDescending { it.value.size }.take(20)
        val sem = Semaphore(6)
        top.map { (name, actors) ->
            async { sem.withPermit { runCatching { gh.repo(name) to actors.toSet() }.getOrNull() } }
        }.awaitAll().filterNotNull()
    }

    // ---- 2. Por topics ----------------------------------------------------------------------

    private suspend fun topical(profile: Profile, now: Instant): List<GhRepo> = coroutineScope {
        val since = date(now, 365)
        val queries = profile.top(4, "t:").map { "topic:${Features.label(it.first)} stars:>30 pushed:>$since" }
            .ifEmpty { listOf("stars:>500 pushed:>$since") }
        queries.map { q ->
            async { runCatching { gh.search(q, page = random.nextInt(1, 4), perPage = 25) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    // ---- 3. Tendencias ----------------------------------------------------------------------

    private suspend fun trending(profile: Profile, now: Instant): List<GhRepo> = coroutineScope {
        val since = date(now, 30)
        val langs = profile.top(2, "l:").map { Features.label(it.first) }
        val queries = listOf("created:>$since stars:>80") + langs.map { "created:>$since stars:>20 language:\"$it\"" }
        queries.map { q -> async { runCatching { gh.search(q, perPage = 25) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
    }

    // ---- 4. Co-estrellas (colaborativo) -----------------------------------------------------

    private class CoStar(val repo: GhRepo, val count: Int, val via: String)

    private suspend fun coStars(login: String, starred: List<StarredItem>): List<CoStar> = coroutineScope {
        // Semillas: repos marcados recientemente, ni enormes (ruido) ni minúsculos (sin gente).
        val seeds = starred.take(25).map { it.repo }.filter { it.stars in 20..60_000 }.shuffled(random).take(3)
        val sem = Semaphore(6)
        val users: List<Pair<String, String>> = seeds.map { seed ->
            async {
                sem.withPermit {
                    // La última página de stargazers son los más recientes (= gente activa ahora).
                    val last = ceil(seed.stars / 30.0).toInt().coerceIn(1, 100)
                    runCatching { gh.stargazers(seed.fullName, last) }.getOrDefault(emptyList())
                        .filter { !it.login.equals(login, true) }
                        .shuffled(random).take(4).map { it.login to seed.fullName }
                }
            }
        }.awaitAll().flatten()

        val counts = HashMap<String, CoStar>()
        users.map { (u, via) ->
            async { sem.withPermit { runCatching { gh.userStarred(u) }.getOrDefault(emptyList()).map { it to via } } }
        }.awaitAll().flatten().forEach { (repo, via) ->
            val k = repo.fullName.lowercase()
            counts[k] = counts[k]?.let { CoStar(it.repo, it.count + 1, it.via) } ?: CoStar(repo, 1, via)
        }
        counts.values.toList()
    }

    // ---- Actividad de amigos ----------------------------------------------------------------

    suspend fun activity(login: String): List<Activity> =
        gh.receivedEvents(login, pages = 1).mapNotNull { e ->
            activityKind(e)?.let { Activity(e.actor, it, e.repo.name, e.createdAt) }
        }

    // ---- Pequeñas joyas ---------------------------------------------------------------------

    suspend fun gems(profile: Profile, exclude: Set<String>, now: Instant = Instant.now()): List<Scored> = coroutineScope {
        val pushed = date(now, 120)
        val created = date(now, 540)
        val topics = profile.top(5, "t:").map { Features.label(it.first) }
        val langs = profile.top(1, "l:").map { Features.label(it.first) }
        val queries = (topics.map { "topic:$it stars:5..400 pushed:>$pushed" } +
            langs.map { "language:\"$it\" stars:10..300 created:>$created pushed:>$pushed" })
            .ifEmpty { listOf("stars:20..300 created:>$created pushed:>$pushed") }
        val found = queries.map { q ->
            async { runCatching { gh.search(q, sort = "updated", perPage = 40) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        GemScorer.rank(found, profile, exclude, now)
    }

    companion object {
        fun activityKind(e: GhEvent): String? = when (e.type) {
            "WatchEvent", "ForkEvent", "PublicEvent", "ReleaseEvent" -> e.type
            "CreateEvent" -> if (e.payload?.get("ref_type")?.jsonPrimitive?.content == "repository") e.type else null
            else -> null
        }

        private fun date(now: Instant, daysAgo: Long): String =
            LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(daysAgo).toString()
    }
}
