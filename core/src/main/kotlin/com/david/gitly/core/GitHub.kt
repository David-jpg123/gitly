package com.david.gitly.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GitHubException(val code: Int, message: String) : IOException(message)

/** Cliente mínimo de la API REST de GitHub. Todas las llamadas son suspend y van en IO. */
class GitHub(
    private val token: String?,
    val http: OkHttpClient = defaultClient(),
) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    /** Llamadas restantes según la última respuesta (cabecera x-ratelimit-remaining). */
    @Volatile var rateRemaining: Int = -1
        private set

    private fun request(path: String, accept: String) = Request.Builder()
        .url("https://api.github.com$path")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    private suspend fun raw(
        path: String,
        accept: String = "application/vnd.github+json",
        method: String = "GET",
        body: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = request(path, accept)
        when (method) {
            "PATCH" -> b.patch((body ?: "{}").toRequestBody("application/json".toMediaType()))
            "POST" -> b.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
            "PUT" -> b.put(ByteArray(0).toRequestBody())
            "DELETE" -> b.delete()
        }
        http.newCall(b.build()).execute().use { r ->
            r.header("x-ratelimit-remaining")?.toIntOrNull()?.let { rateRemaining = it }
            r.code to (r.body?.string() ?: "")
        }
    }

    private suspend fun get(path: String, accept: String = "application/vnd.github+json"): String {
        val (code, body) = raw(path, accept)
        if (code !in 200..299) {
            val msg = runCatching {
                (json.parseToJsonElement(body) as JsonObject)["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: body.take(200)
            throw GitHubException(code, "GitHub $code: $msg")
        }
        return body
    }

    private inline fun <reified T> String.parse(): T = json.decodeFromString(this)

    suspend fun me(): GhUser = get("/user").parse()
    suspend fun user(login: String): GhUser = get("/users/$login").parse()

    /** Edita el perfil propio (name, bio, company, location, blog, twitter_username). Necesita el permiso `user`. */
    suspend fun updateProfile(changes: Map<String, String>): GhUser {
        val body = JsonObject(changes.mapValues { JsonPrimitive(it.value) }).toString()
        val (code, resp) = raw("/user", method = "PATCH", body = body)
        if (code !in 200..299) throw GitHubException(code, "No se pudo guardar el perfil ($code)")
        return resp.parse()
    }

    /** Repos propios (sin forks), los más recientes primero. */
    suspend fun userRepos(login: String, perPage: Int = 60): List<GhRepo> =
        get("/users/$login/repos?per_page=$perPage&sort=pushed&type=owner").parse<List<GhRepo>>().filter { !it.fork }

    suspend fun followers(login: String, perPage: Int = 100): List<GhOwner> = get("/users/$login/followers?per_page=$perPage").parse()
    suspend fun following(login: String, perPage: Int = 100): List<GhOwner> = get("/users/$login/following?per_page=$perPage").parse()

    suspend fun isFollowing(login: String): Boolean = raw("/user/following/$login").first == 204

    /** ¿[a] sigue a [b]? (público, no necesita permisos). */
    suspend fun follows(a: String, b: String): Boolean = raw("/users/$a/following/$b").first == 204

    // ---- Gists: así cada usuario publica su clave de mensajería ------------------------------

    suspend fun userGists(login: String): List<GhGist> = get("/users/$login/gists?per_page=100").parse()

    /** Contenido de un fichero de gist (raw_url, fuera de la API: no gasta cupo). */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw GitHubException(r.code, "GET $url ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    suspend fun createGist(description: String, filename: String, content: String, public: Boolean = true): GhGist {
        val body = JsonObject(mapOf(
            "description" to JsonPrimitive(description),
            "public" to JsonPrimitive(public),
            "files" to JsonObject(mapOf(filename to JsonObject(mapOf("content" to JsonPrimitive(content))))),
        )).toString()
        val (code, resp) = raw("/gists", method = "POST", body = body)
        if (code !in 200..299) throw GitHubException(code, "No se pudo crear el gist ($code)")
        return resp.parse()
    }

    suspend fun updateGist(id: String, filename: String, content: String): GhGist {
        val body = JsonObject(mapOf(
            "files" to JsonObject(mapOf(filename to JsonObject(mapOf("content" to JsonPrimitive(content))))),
        )).toString()
        val (code, resp) = raw("/gists/$id", method = "PATCH", body = body)
        if (code !in 200..299) throw GitHubException(code, "No se pudo actualizar el gist ($code)")
        return resp.parse()
    }

    suspend fun setFollow(login: String, on: Boolean) {
        val (code, body) = raw("/user/following/$login", method = if (on) "PUT" else "DELETE")
        if (code !in 200..299) throw GitHubException(code, "No se pudo cambiar el seguimiento ($code): ${body.take(120)}")
    }

    /** Bloquea (o desbloquea) a [login] en GitHub: deja de seguirte y no puede interactuar contigo. */
    suspend fun setBlock(login: String, on: Boolean) {
        val (code, body) = raw("/user/blocks/$login", method = if (on) "PUT" else "DELETE")
        if (code !in 200..299) throw GitHubException(code, "No se pudo cambiar el bloqueo ($code): ${body.take(120)}")
    }

    /** Imágenes y texto plano del README para el feed; null si no tiene. */
    suspend fun readmePreview(fullName: String): ReadmePreview? {
        val (code, body) = raw("/repos/$fullName/readme")
        if (code == 404) return null
        if (code !in 200..299) throw GitHubException(code, "README $code")
        val f: ReadmeFile = body.parse()
        val md = String(java.util.Base64.getMimeDecoder().decode(f.content), Charsets.UTF_8)
        return ReadmeParser.parse(md, fullName, f.path)
    }

    /** Repos con estrella del usuario autenticado, con fecha (más recientes primero). */
    suspend fun starred(pages: Int = 3): List<StarredItem> = kotlinx.coroutines.coroutineScope {
        suspend fun page(p: Int): List<StarredItem> = get(
            "/user/starred?per_page=100&page=$p&sort=created&direction=desc",
            accept = "application/vnd.github.star+json",
        ).parse()
        val first = page(1)
        if (first.size < 100 || pages == 1) return@coroutineScope first
        // Más páginas en paralelo en vez de una tras otra.
        val rest = (2..pages).map { p -> async { runCatching { page(p) }.getOrDefault(emptyList()) } }.awaitAll()
        first + rest.flatten()
    }

    suspend fun isStarred(fullName: String): Boolean = raw("/user/starred/$fullName").first == 204

    suspend fun setStar(fullName: String, on: Boolean) {
        val (code, body) = raw("/user/starred/$fullName", method = if (on) "PUT" else "DELETE")
        if (code !in 200..299) throw GitHubException(code, "No se pudo cambiar la estrella ($code): ${body.take(120)}")
    }

    suspend fun receivedEvents(login: String, pages: Int = 2): List<GhEvent> {
        val out = ArrayList<GhEvent>()
        for (p in 1..pages) {
            val page: List<GhEvent> = get("/users/$login/received_events?per_page=100&page=$p").parse()
            out += page
            if (page.size < 100) break
        }
        return out
    }

    suspend fun search(q: String, sort: String = "stars", page: Int = 1, perPage: Int = 30): List<GhRepo> {
        val qs = URLEncoder.encode(q, "UTF-8")
        val sortPart = if (sort == "best") "" else "&sort=$sort&order=desc"
        return get("/search/repositories?q=$qs$sortPart&per_page=$perPage&page=$page")
            .parse<SearchResult>().items
    }

    suspend fun repo(fullName: String): GhRepo = get("/repos/$fullName").parse()

    /** README ya renderizado a HTML por GitHub, o null si no tiene. */
    suspend fun readmeHtml(fullName: String): String? {
        val (code, body) = raw("/repos/$fullName/readme", accept = "application/vnd.github.html+json")
        return if (code == 200) body else null
    }

    suspend fun releases(fullName: String, perPage: Int = 6): List<GhRelease> =
        get("/repos/$fullName/releases?per_page=$perPage").parse<List<GhRelease>>().filter { !it.draft }

    /** Página de stargazers; la última página contiene a los más recientes. */
    suspend fun stargazers(fullName: String, page: Int, perPage: Int = 30): List<GhOwner> =
        get("/repos/$fullName/stargazers?per_page=$perPage&page=$page").parse()

    suspend fun userStarred(login: String, perPage: Int = 30): List<GhRepo> =
        get("/users/$login/starred?per_page=$perPage&sort=created").parse()

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .build()
    }
}
