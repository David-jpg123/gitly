package com.david.gitly.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GhOwner(
    val login: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
)

@Serializable
data class GhRepo(
    val id: Long,
    @SerialName("full_name") val fullName: String,
    val name: String,
    val owner: GhOwner,
    val description: String? = null,
    val language: String? = null,
    val topics: List<String> = emptyList(),
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val homepage: String? = null,
    val fork: Boolean = false,
    val archived: Boolean = false,
) {
    /** Heurística barata (sin llamadas extra) de si parece una app Android. */
    val looksAndroid: Boolean
        get() = topics.any { it.contains("android") } ||
            description?.contains("android", ignoreCase = true) == true
}

@Serializable
data class StarredItem(
    @SerialName("starred_at") val starredAt: String,
    val repo: GhRepo,
)

@Serializable
data class SearchResult(val items: List<GhRepo> = emptyList())

@Serializable
data class GhUser(
    val login: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    val name: String? = null,
    val bio: String? = null,
    val followers: Int = 0,
    val following: Int = 0,
    @SerialName("public_repos") val publicRepos: Int = 0,
    val company: String? = null,
    val location: String? = null,
    val blog: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val type: String = "User",
    @SerialName("twitter_username") val twitter: String? = null,
)

@Serializable
data class GhGistFile(val filename: String = "", @SerialName("raw_url") val rawUrl: String = "")

@Serializable
data class GhGist(
    val id: String,
    val description: String? = null,
    val public: Boolean = true,
    val files: Map<String, GhGistFile> = emptyMap(),
)

@Serializable
data class GhEventRepo(val id: Long = 0, val name: String)

@Serializable
data class GhEvent(
    val id: String,
    val type: String,
    val actor: GhOwner,
    val repo: GhEventRepo,
    @SerialName("created_at") val createdAt: String,
    val payload: JsonObject? = null,
)

@Serializable
data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("download_count") val downloads: Int = 0,
    @SerialName("browser_download_url") val url: String,
    @SerialName("content_type") val contentType: String = "",
) {
    val isApk get() = name.endsWith(".apk", ignoreCase = true)
}

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhAsset> = emptyList(),
)
