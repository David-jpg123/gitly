package com.david.gitly.core

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Vector de rasgos de un repo. Prefijos:
 *  t: topic · l: lenguaje · w: palabra de la descripción, del nombre o de un topic.
 */
object Features {
    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "from", "your", "you", "are", "into", "using",
        "based", "simple", "fast", "small", "tool", "tools", "library", "project", "written", "built",
        "make", "made", "allows", "easy", "easily", "about", "more", "than", "also", "which", "will",
        "can", "use", "used", "like", "other", "their", "them", "over", "only", "just", "very", "all",
        "para", "con", "una", "los", "las", "del", "que", "por", "como", "este", "esta", "app",
        "open", "source", "free", "github", "repository", "repo", "awesome", "official", "support",
        "new", "any", "way", "lets", "not", "but", "has", "have", "its", "it's", "get", "set",
    )
    private val WORD = Regex("[a-z][a-z0-9+#]{2,}")

    fun words(text: String?): List<String> =
        if (text == null) emptyList()
        else WORD.findAll(text.lowercase()).map { it.value }.filter { it !in STOP }.toList()

    fun of(r: GhRepo): Map<String, Double> {
        val m = HashMap<String, Double>()
        for (t in r.topics) {
            m["t:$t"] = 1.0
            for (w in t.split('-')) if (w.length >= 3 && w !in STOP) m.merge("w:$w", 0.3, Double::plus)
        }
        r.language?.let { m["l:${it.lowercase()}"] = 0.55 }
        words(r.description).distinct().take(14).forEach { m.merge("w:$it", 0.35, Double::plus) }
        words(r.name.replace('-', ' ').replace('_', ' ')).forEach { m.merge("w:$it", 0.25, Double::plus) }
        return normalize(m)
    }

    fun normalize(m: Map<String, Double>): Map<String, Double> {
        val n = sqrt(m.values.sumOf { it * it })
        return if (n == 0.0) m else m.mapValues { it.value / n }
    }

    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (s, l) = if (a.size < b.size) a to b else b to a
        var dot = 0.0
        for ((k, v) in s) l[k]?.let { dot += v * it }
        return dot
    }

    fun label(token: String) = token.substringAfter(':')
}

/** Una señal de gusto: estrella, like explícito (+), "no me interesa" (−), apertura del detalle (+ débil). */
data class Signal(val repo: GhRepo, val weight: Double, val at: Instant)

/**
 * Perfil de gustos: suma de los rasgos de cada señal con decaimiento temporal
 * (vida media [halfLifeDays]). Las señales negativas se acumulan aparte y restan.
 */
@Serializable
class Profile(val positive: Map<String, Double>, val negative: Map<String, Double>) {

    val isEmpty get() = positive.isEmpty()

    fun similarity(features: Map<String, Double>): Double =
        Features.cosine(positive, features) - 0.6 * Features.cosine(negative, features)

    fun top(n: Int, prefix: String? = null): List<Pair<String, Double>> =
        positive.entries.filter { prefix == null || it.key.startsWith(prefix) }
            .sortedByDescending { it.value }.take(n).map { it.key to it.value }

    /** Rasgos en común entre el perfil y un repo, para explicar la recomendación. */
    fun overlap(features: Map<String, Double>, n: Int = 2): List<String> =
        features.entries.mapNotNull { (k, v) -> positive[k]?.let { k to it * v } }
            .sortedByDescending { it.second }.map { Features.label(it.first) }.distinct().take(n)

    companion object {
        fun build(
            signals: List<Signal>,
            now: Instant = Instant.now(),
            halfLifeDays: Double = 240.0,
            maxTokens: Int = 400,
        ): Profile {
            val pos = HashMap<String, Double>()
            val neg = HashMap<String, Double>()
            for (s in signals) {
                val days = Duration.between(s.at, now).toHours().coerceAtLeast(0) / 24.0
                val decay = exp(-ln(2.0) * days / halfLifeDays)
                val target = if (s.weight >= 0) pos else neg
                val w = abs(s.weight) * decay
                for ((k, v) in Features.of(s.repo)) target.merge(k, v * w, Double::plus)
            }
            fun trim(m: Map<String, Double>) = Features.normalize(
                m.entries.sortedByDescending { it.value }.take(maxTokens).associate { it.key to it.value }
            )
            return Profile(trim(pos), trim(neg))
        }
    }
}

enum class Source { SOCIAL, TOPIC, TRENDING, COSTAR }

/** Un repo candidato con las fuentes que lo han traído. */
class Candidate(val repo: GhRepo) {
    val sources = HashSet<Source>()
    val socialBy = LinkedHashSet<String>()
    var coStars = 0
    var coStarVia: String? = null
}

@Serializable
enum class ReasonKind { SOCIAL, INTEREST, COSTAR, TRENDING, RELATED, POPULAR, GEM, REPOST }

/** Por qué se recomienda algo. La app lo traduce; [args] son nombres/topics, [extra] un recuento. */
@Serializable
data class Reason(val kind: ReasonKind, val args: List<String> = emptyList(), val extra: Int = 0)

@Serializable
data class Scored(
    val repo: GhRepo,
    val score: Double,
    val reason: Reason,
    val socialBy: List<String>,
)

/** Puntuación + reordenación por diversidad (MMR) de los candidatos. */
object Ranker {
    fun rank(
        candidates: Collection<Candidate>,
        profile: Profile,
        exclude: Set<String>,
        impressions: Map<String, Int> = emptyMap(),
        now: Instant = Instant.now(),
        limit: Int = 60,
    ): List<Scored> {
        class Row(val c: Candidate, val f: Map<String, Double>, val base: Double, val sim: Double)

        val cold = profile.isEmpty
        val rows = candidates
            .filter { it.repo.fullName.lowercase() !in exclude && it.repo.name != ".github" }
            .map { c ->
                val r = c.repo
                val f = Features.of(r)
                val sim = if (cold) 0.0 else profile.similarity(f)
                val social = 1 - exp(-0.7 * c.socialBy.size)
                val co = 1 - exp(-0.6 * c.coStars)
                val pop = (log10(r.stars + 1.0) / 5.0).coerceIn(0.0, 1.0)
                val pushedDays = r.pushedAt?.let { runCatching { Duration.between(Instant.parse(it), now).toDays() }.getOrNull() } ?: 999
                val fresh = exp(-pushedDays / 150.0)
                val trending = if (Source.TRENDING in c.sources) 1.0 else 0.0
                val penalty = 0.12 * (impressions[r.fullName.lowercase()] ?: 0) +
                    (if (r.archived) 0.3 else 0.0) + (if (r.fork) 0.15 else 0.0)
                val base = (if (cold) 0.0 else 0.5 * sim) + 0.2 * social + 0.12 * co + 0.1 * pop +
                    0.08 * fresh + (if (cold) 0.15 else 0.04) * trending - penalty
                Row(c, f, base, sim)
            }
            .sortedByDescending { it.base }
            .take(limit * 3)
            .toMutableList()

        // MMR: escoger iterativamente penalizando parecido con lo ya elegido y dueños repetidos.
        val picked = ArrayList<Row>()
        val ownerCount = HashMap<String, Int>()
        while (picked.size < limit && rows.isNotEmpty()) {
            val best = rows.maxBy { r ->
                val maxSim = picked.maxOfOrNull { Features.cosine(it.f, r.f) } ?: 0.0
                r.base - 0.2 * maxSim - 0.12 * (ownerCount[r.c.repo.owner.login] ?: 0)
            }
            rows.remove(best)
            picked += best
            ownerCount.merge(best.c.repo.owner.login, 1, Int::plus)
        }

        return picked.map { r -> Scored(r.c.repo, r.base, reason(r.c, profile, r.f, r.sim), r.c.socialBy.toList()) }
    }

    private fun reason(c: Candidate, profile: Profile, f: Map<String, Double>, sim: Double): Reason {
        val overlap = if (profile.isEmpty) emptyList() else profile.overlap(f)
        return when {
            c.socialBy.isNotEmpty() -> Reason(ReasonKind.SOCIAL, c.socialBy.take(2), (c.socialBy.size - 2).coerceAtLeast(0))
            sim > 0.12 && overlap.isNotEmpty() -> Reason(ReasonKind.INTEREST, overlap)
            c.coStars > 0 -> Reason(ReasonKind.COSTAR, listOfNotNull(c.coStarVia?.substringAfter('/')))
            Source.TRENDING in c.sources -> Reason(ReasonKind.TRENDING)
            overlap.isNotEmpty() -> Reason(ReasonKind.RELATED, overlap)
            else -> Reason(ReasonKind.POPULAR)
        }
    }
}

/**
 * "Pequeñas joyas": repos con pocas estrellas pero que encajan contigo, están vivos,
 * bien documentados y ganan estrellas deprisa para su edad.
 */
object GemScorer {
    fun rank(
        repos: Collection<GhRepo>,
        profile: Profile,
        exclude: Set<String>,
        now: Instant = Instant.now(),
        limit: Int = 20,
    ): List<Scored> {
        val scored = repos.asSequence()
            .distinctBy { it.id }
            .filter { it.fullName.lowercase() !in exclude && !it.fork && !it.archived }
            .filter { it.stars in 5..600 && !it.description.isNullOrBlank() && it.name != ".github" }
            .map { r ->
                val f = Features.of(r)
                val sim = if (profile.isEmpty) 0.0 else profile.similarity(f)
                val quality = (minOf(r.topics.size, 5) / 5.0) * 0.5 +
                    (if (!r.homepage.isNullOrBlank()) 0.25 else 0.0) +
                    (if ((r.description?.length ?: 0) > 40) 0.25 else 0.0)
                val ageDays = r.createdAt?.let { runCatching { Duration.between(Instant.parse(it), now).toDays() }.getOrNull() } ?: 365
                val perMonth = r.stars / (ageDays / 30.0).coerceAtLeast(1.0)
                val velocity = (log10(perMonth + 1) / 2.0).coerceIn(0.0, 1.0)
                val pushedDays = r.pushedAt?.let { runCatching { Duration.between(Instant.parse(it), now).toDays() }.getOrNull() } ?: 999
                val fresh = exp(-pushedDays / 60.0)
                val score = 0.55 * sim + 0.15 * quality + 0.2 * velocity + 0.1 * fresh
                val tag = profile.overlap(f, 1).firstOrNull() ?: r.topics.firstOrNull() ?: r.language?.lowercase()
                Scored(r, score, Reason(ReasonKind.GEM, listOfNotNull(tag)), emptyList())
            }
            .sortedByDescending { it.score }
            .toList()
        // Máximo dos por dueño para que la sección no la acapare nadie.
        val perOwner = HashMap<String, Int>()
        return scored.filter { perOwner.merge(it.repo.owner.login, 1, Int::plus)!! <= 2 }.take(limit)
    }
}
