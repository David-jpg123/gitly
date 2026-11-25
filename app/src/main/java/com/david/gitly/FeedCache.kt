package com.david.gitly

import android.content.Context
import com.david.gitly.core.Profile
import com.david.gitly.core.ReadmePreview
import com.david.gitly.core.Scored
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Último feed calculado, para abrir la app al instante sin esperar a la red. */
@Serializable
class FeedSnapshot(
    val login: String,
    val savedAt: Long,
    val items: List<Scored>,
    val profile: Profile? = null,
    val starred: Set<String> = emptySet(),
)

/** Caché en disco (se puede borrar sin perder nada: se regenera). */
class FeedCache(context: Context) {
    private val dir = File(context.cacheDir, "feed").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun feedFile(login: String) = File(dir, "feed-${login.lowercase()}.json")

    fun loadFeed(login: String): FeedSnapshot? =
        runCatching { json.decodeFromString<FeedSnapshot>(feedFile(login).readText()) }.getOrNull()

    fun saveFeed(s: FeedSnapshot) = write(feedFile(s.login), json.encodeToString(FeedSnapshot.serializer(), s))

    fun loadPreviews(): Map<String, ReadmePreview> =
        runCatching { json.decodeFromString<Map<String, ReadmePreview>>(File(dir, "previews.json").readText()) }.getOrDefault(emptyMap())

    /** Guarda como mucho [max] vistas previas (las últimas añadidas). */
    fun savePreviews(map: Map<String, ReadmePreview>, max: Int = 600) {
        val trimmed = if (map.size > max) map.entries.toList().takeLast(max).associate { it.toPair() } else map
        write(File(dir, "previews.json"), json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), ReadmePreview.serializer()), trimmed))
    }

    @Synchronized
    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
