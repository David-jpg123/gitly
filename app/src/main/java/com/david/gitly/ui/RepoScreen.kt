package com.david.gitly.ui

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.david.gitly.ApkInstaller
import com.david.gitly.AppViewModel
import com.david.gitly.RepoDetails
import com.david.gitly.R
import com.david.gitly.Route
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.david.gitly.core.GhRelease

@Composable
fun RepoScreen(vm: AppViewModel, fullName: String, onBack: () -> Unit, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf<RepoDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fullName) {
        try {
            vm.details(fullName).also { details = it; vm.opened(it.repo) }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                IconButton(onClick = { openUrl(ctx, "https://github.com/$fullName") }) {
                    Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.open_in_github))
                }
            },
        )
        val d = details
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> RepoBody(vm, d, open)
        }
    }
}

@Composable
private fun RepoBody(vm: AppViewModel, d: RepoDetails, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    val repo = d.repo
    val starred = repo.fullName.lowercase() in vm.starredSet
    // Última release (preferiblemente estable) que traiga APKs.
    val apkRelease: GhRelease? = d.releases.firstOrNull { !it.prerelease && it.assets.any { a -> a.isApk } }
        ?: d.releases.firstOrNull { it.assets.any { a -> a.isApk } }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable { open(Route.User(repo.owner.login)) }) { Avatar(repo.owner.avatarUrl, 48) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(repo.owner.login, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(repo.name, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        repo.description?.takeIf { it.isNotBlank() }?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("★ ${compact(repo.stars)}", style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.n_forks, compact(repo.forks)), style = MaterialTheme.typography.labelLarge)
                repo.language?.let { LanguageDot(it) }
                Text(stringResource(R.string.updated_ago, relativeTime(repo.pushedAt)), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (repo.topics.isNotEmpty()) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repo.topics.forEach { TopicChip(it) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.toggleStar(repo) }) {
                    Icon(if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                        tint = if (starred) Color(0xFFE3B341) else Color.Unspecified)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (starred) R.string.starred_btn else R.string.star_btn))
                }
                OutlinedButton(onClick = { shareRepo(ctx, repo) }) {
                    Icon(Icons.Outlined.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share))
                }
            }
            repo.homepage?.takeIf { it.isNotBlank() }?.let { url ->
                Text(url, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (apkRelease != null) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.install_app), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.version_line, apkRelease.tag, relativeTime(apkRelease.publishedAt)) +
                            (if (apkRelease.prerelease) " · " + stringResource(R.string.prerelease) else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ApkInstaller.rank(apkRelease.assets).take(5).forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.apk_meta, "%.1f".format(a.size / 1_048_576.0), compact(a.downloads)),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val p = vm.downloads[a.url]
                            if (p != null) {
                                CircularProgressIndicator(progress = { p }, modifier = Modifier.size(32.dp))
                            } else {
                                FilledTonalButton(onClick = { vm.installApk(ctx, a) }) {
                                    Icon(Icons.Outlined.Download, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.install))
                                }
                            }
                        }
                    }
                }
            }
        } else if (repo.looksAndroid) item {
            Text(stringResource(R.string.no_apks), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        d.readmeHtml?.let { html -> item { Readme(html, repo.fullName) } }
    }
}

@Composable
private fun Readme(html: String, fullName: String) {
    val fg = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val code = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    fun hex(c: Int) = "#%06X".format(0xFFFFFF and c)
    val doc = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;color:${hex(fg)};background:transparent;margin:0;font-size:15px;line-height:1.5;overflow-wrap:anywhere}
          a{color:${hex(link)}} img{max-width:100%;height:auto}
          pre,code{background:${hex(code)};border-radius:6px;font-size:13px} pre{padding:10px;overflow-x:auto}
          table{display:block;overflow-x:auto;border-collapse:collapse} td,th{border:1px solid ${hex(code)};padding:4px 8px}
          a.anchor,svg.octicon{display:none} h1,h2{border-bottom:1px solid ${hex(code)};padding-bottom:4px}
        </style></head><body>$html</body></html>
    """.trimIndent()
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0)
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                // Las rutas relativas de imágenes del README se resuelven contra la rama por defecto.
                loadDataWithBaseURL("https://github.com/$fullName/raw/HEAD/", doc, "text/html", "utf-8", null)
            }
        },
    )
}
