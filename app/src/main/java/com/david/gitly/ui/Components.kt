package com.david.gitly.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.david.gitly.R
import com.david.gitly.UiText
import com.david.gitly.core.GhRepo
import com.david.gitly.core.Reason
import com.david.gitly.core.ReasonKind
import java.time.Duration
import java.time.Instant

val StarColor = Color(0xFFF2B84B)
val AndroidGreen = Color(0xFF3DDC84)

fun UiText.resolve(ctx: Context): String = when (this) {
    is UiText.Res -> ctx.getString(id, *args.toTypedArray())
    is UiText.Raw -> text
}

@Composable
fun UiText.text(): String = resolve(LocalContext.current)

/** Tarjeta social que GitHub genera para cada repo (no gasta llamadas a la API). [n] debe ser numérico. */
fun ogImage(fullName: String, n: Int = 1) = "https://opengraph.githubassets.com/$n/$fullName"

/**
 * Tarjeta social del repo. GitHub responde 429 si se piden muchas a la vez, así que reintenta
 * con espera creciente; debajo siempre queda el avatar del dueño como respaldo.
 */
@Composable
fun OgImage(repo: GhRepo, modifier: Modifier = Modifier) {
    var attempt by remember(repo.fullName) { mutableIntStateOf(0) }
    var errors by remember(repo.fullName) { mutableIntStateOf(0) }
    LaunchedEffect(errors) {
        if (errors in 1..4) { delay(1200L * errors + (0..800).random()); attempt = errors }
    }
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Avatar(repo.owner.avatarUrl, 44)
        AsyncImage(
            model = ogImage(repo.fullName, attempt + 1), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            onError = { errors = attempt + 1 },
        )
    }
}

@Composable
fun reasonText(r: Reason): String = when (r.kind) {
    ReasonKind.SOCIAL -> {
        val names = r.args.joinToString(stringResource(R.string.and_sep)) { "@$it" }
        if (r.extra > 0) stringResource(R.string.reason_social_more, names, r.extra) else stringResource(R.string.reason_social, names)
    }
    ReasonKind.INTEREST -> stringResource(R.string.reason_interest, r.args.joinToString(", "))
    ReasonKind.COSTAR -> stringResource(R.string.reason_costar, r.args.firstOrNull() ?: "…")
    ReasonKind.TRENDING -> stringResource(R.string.reason_trending)
    ReasonKind.RELATED -> stringResource(R.string.reason_related, r.args.joinToString(", "))
    ReasonKind.POPULAR -> stringResource(R.string.reason_popular)
    ReasonKind.GEM -> r.args.firstOrNull()?.let { "#$it" } ?: stringResource(R.string.gems_title)
    ReasonKind.REPOST -> {
        val names = r.args.joinToString(stringResource(R.string.and_sep)) { "@$it" }
        when {
            r.extra > 0 -> stringResource(R.string.reason_repost_more, names, r.extra)
            r.args.size > 1 -> stringResource(R.string.reason_repost_many, names)
            else -> stringResource(R.string.reason_repost, names)
        }
    }
}

@Composable
fun Avatar(url: String, size: Int) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.dp.roundToPx() }
    AsyncImage(
        model = avatarUrl(url, px),
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun TopicChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun LanguageDot(language: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(languageColor(language)))
        Spacer(Modifier.width(4.dp))
        Text(language, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun languageColor(l: String): Color = when (l.lowercase()) {
    "kotlin" -> Color(0xFFA97BFF); "java" -> Color(0xFFB07219); "rust" -> Color(0xFFDEA584)
    "python" -> Color(0xFF3572A5); "typescript" -> Color(0xFF3178C6); "javascript" -> Color(0xFFF1E05A)
    "go" -> Color(0xFF00ADD8); "c" -> Color(0xFF555555); "c++" -> Color(0xFFF34B7D); "qml" -> Color(0xFF44A51C)
    "shell" -> Color(0xFF89E051); "dart" -> Color(0xFF00B4AB); "swift" -> Color(0xFFF05138); "lua" -> Color(0xFF000080)
    else -> Color(0xFF8B949E)
}

/** Casilla de repo para rejillas (Explorar, perfil). */
@Composable
fun RepoTile(repo: GhRepo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        OgImage(repo, Modifier.fillMaxWidth().aspectRatio(2f))
        Column(Modifier.padding(10.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(repo.description.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = StarColor)
                Spacer(Modifier.width(3.dp))
                Text(compact(repo.stars), style = MaterialTheme.typography.labelMedium)
                repo.language?.let { Spacer(Modifier.width(8.dp)); LanguageDot(it) }
            }
        }
    }
}

/** Destello de carga (skeleton) con los colores del tema, en vez de ruedas giratorias. */
@Composable
fun Modifier.shimmer(): Modifier {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        -1f, 2f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.LinearEasing)),
        label = "x",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    return this.drawWithCache {
        val w = size.width
        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(base, hi, base),
            start = androidx.compose.ui.geometry.Offset(w * x - w / 2, 0f),
            end = androidx.compose.ui.geometry.Offset(w * x + w / 2, size.height),
        )
        onDrawBehind { drawRect(brush) }
    }
}

fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

@Composable
fun relativeTime(iso: String?): String {
    val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 60 -> stringResource(R.string.time_min, d.toMinutes().coerceAtLeast(1))
        d.toHours() < 24 -> stringResource(R.string.time_h, d.toHours())
        d.toDays() < 30 -> stringResource(R.string.time_d, d.toDays())
        d.toDays() < 365 -> stringResource(R.string.time_months, d.toDays() / 30)
        else -> stringResource(R.string.time_years, d.toDays() / 365)
    }
}

fun shareRepo(ctx: Context, repo: GhRepo) {
    val text = buildString {
        append(repo.fullName)
        repo.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        append("\nhttps://github.com/").append(repo.fullName)
    }
    share(ctx, text, ctx.getString(R.string.share_repo))
}

fun shareUser(ctx: Context, login: String) = share(ctx, "https://github.com/$login", ctx.getString(R.string.share_profile))

fun share(ctx: Context, text: String, title: String = ctx.getString(R.string.share)) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private val GH_PNG = Regex("^https://github\\.com/([A-Za-z0-9-]+)\\.png(\\?.*)?$")

/**
 * Avatar al tamaño en que se pinta, directo de avatars.githubusercontent.com: github.com/x.png redirige
 * (dos conexiones) y la URL de la API da la foto entera (~30 KB frente a ~3 KB). El tamaño se redondea
 * a unos pocos escalones para que la caché se comparta entre pantallas.
 */
fun avatarUrl(url: String, px: Int): String {
    if (url.isBlank()) return url
    val s = listOf(64, 128, 256, 460).firstOrNull { it >= px } ?: 460
    GH_PNG.find(url)?.let { return "https://avatars.githubusercontent.com/${it.groupValues[1]}?s=$s" }
    if (!url.startsWith("https://avatars.githubusercontent.com/")) return url
    val base = url.replace(Regex("([?&])s=\\d+&?"), "$1").trimEnd('?', '&')
    return base + (if ('?' in base) "&" else "?") + "s=$s"
}
