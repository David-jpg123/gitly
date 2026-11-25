package com.david.gitly.core

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class RecommenderTest {
    private val now = Instant.parse("2026-11-24T00:00:00Z")
    private var nextId = 1L

    private fun repo(name: String, topics: List<String>, lang: String?, desc: String, stars: Int = 1000, owner: String = name) =
        GhRepo(
            id = nextId++, fullName = "$owner/$name", name = name, owner = GhOwner(owner),
            description = desc, language = lang, topics = topics, stars = stars,
            pushedAt = "2026-11-01T00:00:00Z",
        )

    private val kotlinAndroid = listOf(
        repo("glassui", listOf("android", "jetpack-compose", "kotlin"), "Kotlin", "Glass effects for Compose"),
        repo("launcher", listOf("android", "launcher", "kotlin"), "Kotlin", "Minimal Android launcher"),
        repo("musicapp", listOf("android", "music-player", "jetpack-compose"), "Kotlin", "Music player for Android"),
    )

    @Test fun profileFavorsSimilarRepos() {
        val profile = Profile.build(kotlinAndroid.map { Signal(it, 1.0, now) }, now)
        val composeLib = Features.of(repo("compose-x", listOf("jetpack-compose", "android"), "Kotlin", "Compose components"))
        val webLib = Features.of(repo("reactish", listOf("react", "javascript", "frontend"), "TypeScript", "React UI kit"))
        assertTrue(profile.similarity(composeLib) > 0.3, "compose sim ${profile.similarity(composeLib)}")
        assertTrue(profile.similarity(webLib) < 0.05, "web sim ${profile.similarity(webLib)}")
    }

    @Test fun dislikesLowerScore() {
        val launcher2 = repo("launcher2", listOf("android", "launcher"), "Kotlin", "Another Android launcher")
        val pos = kotlinAndroid.map { Signal(it, 1.0, now) }
        val base = Profile.build(pos, now)
        val withDislike = Profile.build(pos + Signal(kotlinAndroid[1], -2.0, now), now)
        assertTrue(withDislike.similarity(Features.of(launcher2)) < base.similarity(Features.of(launcher2)))
    }

    @Test fun oldSignalsDecay() {
        val rust = repo("rusty", listOf("rust", "cli"), "Rust", "A rust command line")
        val profile = Profile.build(listOf(
            Signal(kotlinAndroid[0], 1.0, Instant.parse("2023-01-01T00:00:00Z")),
            Signal(rust, 1.0, now),
        ), now)
        assertTrue(profile.similarity(Features.of(rust)) > profile.similarity(Features.of(kotlinAndroid[0])))
    }

    @Test fun rankerExcludesAndDiversifies() {
        val profile = Profile.build(kotlinAndroid.map { Signal(it, 1.0, now) }, now)
        val cands = buildList {
            repeat(5) { add(Candidate(repo("compose$it", listOf("android", "jetpack-compose"), "Kotlin", "Compose thing $it", owner = "same"))) }
            add(Candidate(repo("other", listOf("android", "kotlin", "launcher"), "Kotlin", "Different launcher", owner = "someone")))
            add(Candidate(kotlinAndroid[0]).also { it.socialBy += "amigo" })
        }
        val ranked = Ranker.rank(cands, profile, exclude = setOf(kotlinAndroid[0].fullName.lowercase()), now = now, limit = 4)
        assertTrue(ranked.none { it.repo.fullName == kotlinAndroid[0].fullName })
        // Con 5 repos del mismo dueño, el de otro dueño debe aparecer en los primeros puestos.
        assertTrue(ranked.take(3).any { it.repo.owner.login == "someone" }, ranked.map { it.repo.fullName }.toString())
        assertTrue(ranked.first { it.repo.owner.login == "someone" }.reason.kind == ReasonKind.INTEREST)
    }

    @Test fun coldStartStillRanks() {
        val c = Candidate(repo("hot", listOf("ai"), "Python", "Trending thing")).also { it.sources += Source.TRENDING }
        val ranked = Ranker.rank(listOf(c), Profile.build(emptyList(), now), emptySet(), now = now)
        assertTrue(ranked.single().reason.kind == ReasonKind.TRENDING)
    }

    @Test fun readmeParser() {
        val md = """
            # MyApp
            [![Build](https://img.shields.io/badge/build-ok-green.svg)](https://ci)
            <p align="center"><img src="docs/logo.png" width="200"></p>
            <img src="tiny.png" width="16">
            A **great** app for [Android](https://android.com).

            ![shot](./screens/1.png "Pantalla")
            ![ref][s2]

            ```kotlin
            val x = 1
            ```
            - Feature one
            | a | b |
            |---|---|

            [s2]: https://github.com/o/r/blob/main/s2.gif
        """.trimIndent()
        val p = ReadmeParser.parse(md, "o/myapp", "README.md")
        assertTrue(p.images == listOf(
            "https://raw.githubusercontent.com/o/myapp/HEAD/docs/logo.png",
            "https://raw.githubusercontent.com/o/myapp/HEAD/screens/1.png",
            "https://raw.githubusercontent.com/o/r/main/s2.gif",
        ), p.images.toString())
        assertTrue(p.text.startsWith("A great app for Android."), p.text)
        assertTrue("• Feature one" in p.text && "val x" !in p.text && "|" !in p.text, p.text)
        assertTrue(ReadmeParser.resolve("../img/a.png", "o/r", "docs/") == "https://raw.githubusercontent.com/o/r/HEAD/img/a.png")
    }

    @Test fun gemsPreferSmallRelevantRepos() {
        val profile = Profile.build(kotlinAndroid.map { Signal(it, 1.0, now) }, now)
        val gem = repo("tinycompose", listOf("android", "jetpack-compose", "kotlin"), "Kotlin", "A tiny but well made Compose library", stars = 120)
            .copy(createdAt = "2026-08-01T00:00:00Z")
        val huge = repo("hugecompose", listOf("android", "jetpack-compose"), "Kotlin", "Huge famous library", stars = 50_000)
        val unrelated = repo("webby", listOf("react"), "TypeScript", "A react thing that is small", stars = 100)
        val r = GemScorer.rank(listOf(gem, huge, unrelated), profile, emptySet(), now)
        assertTrue(r.first().repo.name == "tinycompose" && r.none { it.repo.name == "hugecompose" }, r.map { it.repo.name }.toString())
        assertTrue(r.first().reason.kind == ReasonKind.GEM)
    }

    /** Prueba contra la API real; solo se ejecuta con GH_TOKEN definido. */
    @Test fun liveFeed() {
        val token = System.getenv("GH_TOKEN").orEmpty()
        if (token.isBlank()) { println("GH_TOKEN vacío: se omite la prueba en vivo"); return }
        runBlocking {
            val gh = GitHub(token)
            val me = gh.me()
            val starred = gh.starred(pages = 1)
            val engine = FeedEngine(gh)
            val t0 = System.currentTimeMillis()
            val res = engine.build(me.login, starred, emptyList(), emptyMap(), emptySet())
            println("${starred.size} estrellas, ${res.items.size} recomendaciones en ${System.currentTimeMillis() - t0} ms, rate restante ${gh.rateRemaining}")
            println("Perfil: " + res.profile.top(12).joinToString { "${it.first}=%.2f".format(it.second) })
            res.errors.forEach { println("ERROR $it") }
            res.items.take(15).forEach { println("%.3f  %-45s ★%-6d %s".format(it.score, it.repo.fullName, it.repo.stars, it.reason)) }
            assertTrue(res.items.isNotEmpty())
            val acts = engine.activity(me.login)
            println("Actividad (${acts.size}): " + acts.take(5).joinToString { "@${it.actor.login} ${it.kind} ${it.repoFullName}" })
            val gems = engine.gems(res.profile, starred.map { it.repo.fullName.lowercase() }.toSet())
            println("Joyas (${gems.size}):"); gems.take(10).forEach { println("  %.3f %-40s ★%-4d %s".format(it.score, it.repo.fullName, it.repo.stars, it.reason.args)) }
            for (full in listOf("MM2-0/Kvaesitso", "end-4/dots-hyprland", res.items.first().repo.fullName)) {
                val p = gh.readmePreview(full)
                println("README $full: ${p?.images?.size} imágenes ${p?.images?.take(3)}\n   texto: ${p?.text?.take(160)?.replace('\n', ' ')}")
            }
            val rel = gh.releases("MM2-0/Kvaesitso")
            println("APKs de Kvaesitso: " + rel.firstOrNull()?.assets?.filter { it.isApk }?.map { it.name })
        }
    }
}
