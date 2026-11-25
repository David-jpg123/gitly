package com.david.gitly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalContentColor
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.david.gitly.AppViewModel
import com.david.gitly.R
import com.david.gitly.Route
import com.david.gitly.core.GhRepo
import com.david.gitly.core.ReadmePreview
import com.david.gitly.core.Scored
import kotlinx.coroutines.delay

@Composable
fun FeedScreen(vm: AppViewModel, open: (Route) -> Unit, onOpenChats: () -> Unit = {}) {
    val ctx = LocalContext.current
    var sendTarget by remember { mutableStateOf<GhRepo?>(null) }
    sendTarget?.let { r -> SendSheet(vm, r, onDismiss = { sendTarget = null }) }
    val onSend: (GhRepo) -> Unit = { r -> if (vm.messagingEnabled) sendTarget = r else shareRepo(ctx, r) }
    val listState = vm.feedListState
    // Precarga las vistas previas de los próximos posts mientras se baja.
    LaunchedEffect(listState, vm.feed) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { i ->
            vm.prefetch(vm.displayFeed.drop((i - 1).coerceAtLeast(0)).take(6).map { it.repo })
        }
    }
    PullToRefreshBox(isRefreshing = vm.feedLoading, onRefresh = { vm.refreshFeed() }, modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = vm.pendingFeed != null,
            enter = slideInVertically { -it } + fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).zIndex(1f),
        ) {
            Button(onClick = vm::applyPendingFeed, elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)) {
                Icon(Icons.Filled.ArrowUpward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.new_posts))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                // Barra superior como la de Instagram: marca a la izquierda, mensajes a la derecha.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenChats) {
                        androidx.compose.material3.BadgedBox(badge = {
                            if (vm.unreadTotal > 0) androidx.compose.material3.Badge { Text("${vm.unreadTotal}") }
                        }) { Icon(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.friends_chats)) }
                    }
                }
            }
            val items = vm.displayFeed
            if (items.isEmpty()) item {
                Text(
                    when {
                        vm.feedLoading -> stringResource(R.string.feed_loading)
                        vm.feedError != null -> vm.feedError!!.text()
                        else -> stringResource(R.string.feed_pull)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(items, key = { it.repo.id }) { s ->
                FeedPost(vm, s, open, onSend)
                HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        }
    }
}

@Composable
private fun FeedPost(vm: AppViewModel, s: Scored, open: (Route) -> Unit, onSend: (GhRepo) -> Unit) {
    val ctx = LocalContext.current
    val repo = s.repo
    val k = repo.fullName.lowercase()
    LaunchedEffect(k) { vm.loadPreview(repo); vm.impression(repo) }
    val preview = vm.previews[k]
    val starred = vm.isStarred(repo)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val isRepost = s.reason.kind == com.david.gitly.core.ReasonKind.REPOST
    Column(Modifier.fillMaxWidth()) {
        // Marca de repost, al estilo de Instagram: quién lo ha compartido con sus amigos
        if (isRepost) Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp)
                .clickable { s.socialBy.firstOrNull()?.let { open(Route.User(it)) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            s.socialBy.take(3).forEachIndexed { i, who ->
                Box(Modifier.padding(start = if (i == 0) 0.dp else 0.dp).offset(x = (-6 * i).dp)) {
                    Avatar("https://github.com/$who.png", 20)
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(reasonText(s.reason), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Cabecera: dueño, repo y por qué aparece
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(CircleShape).clickable { open(Route.User(repo.owner.login)) }) { Avatar(repo.owner.avatarUrl, 38) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { open(Route.Repo(repo.fullName)) }) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(repo.owner.login) }
                        append(" / ")
                        append(repo.name)
                    },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!isRepost) Text(reasonText(s.reason), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else repo.language?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            PostMenu(vm, repo, open)
        }

        PostMedia(vm, repo, preview, onOpen = { open(Route.Repo(repo.fullName)) }, onDoubleTap = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            vm.toggleStar(repo, onlyAdd = true)
        })

        // Acciones: estrella, enviar, repostear (pronto)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Estrella con rebote y vibración al marcarla (como el corazón de Instagram)
            val starScale = remember { androidx.compose.animation.core.Animatable(1f) }
            var wasStarred by remember(k) { mutableStateOf(starred) }
            LaunchedEffect(starred) {
                if (starred && !wasStarred) {
                    starScale.animateTo(1.35f, androidx.compose.animation.core.tween(110))
                    starScale.animateTo(1f, spring(dampingRatio = 0.35f))
                }
                wasStarred = starred
            }
            IconButton(onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.toggleStar(repo) }) {
                val m = Modifier.graphicsLayer { scaleX = starScale.value; scaleY = starScale.value }
                if (starred) Icon(Icons.Filled.Star, stringResource(R.string.action_unstar), m, tint = StarColor)
                else Icon(Icons.Outlined.StarBorder, stringResource(R.string.action_star), m)
            }
            IconButton(onClick = { onSend(repo) }) {
                Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.action_send))
            }
            val reposted = vm.isReposted(repo)
            IconButton(onClick = { vm.toggleRepost(repo) }) {
                Icon(Icons.Outlined.Repeat, stringResource(if (reposted) R.string.action_unrepost else R.string.action_repost),
                    tint = if (reposted) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            Spacer(Modifier.weight(1f))
            if (repo.looksAndroid) AssistChip(
                onClick = { open(Route.Repo(repo.fullName)) },
                label = { Text(stringResource(R.string.chip_app)) },
                leadingIcon = { Icon(Icons.Filled.Android, null, Modifier.size(18.dp), tint = AndroidGreen) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        Text(
            pluralStringResource(R.plurals.n_stars, repo.stars, compact(repo.stars)) + (repo.language?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Caption(repo, preview, open)
    }
}

@Composable
private fun PostMenu(vm: AppViewModel, repo: GhRepo, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.not_interested)) },
                leadingIcon = { Icon(Icons.Outlined.ThumbDown, null) },
                onClick = { expanded = false; vm.dislike(repo) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.view_details)) },
                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                onClick = { expanded = false; open(Route.Repo(repo.fullName)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_in_github)) },
                leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                onClick = { expanded = false; openUrl(ctx, "https://github.com/${repo.fullName}") },
            )
        }
    }
}

/** Carrusel de imágenes del README (o la tarjeta social del repo si no tiene). Doble toque = estrella. */
@Composable
private fun PostMedia(vm: AppViewModel, repo: GhRepo, preview: ReadmePreview?, onOpen: () -> Unit, onDoubleTap: () -> Unit) {
    val images = preview?.images.orEmpty()
    var burst by remember { mutableStateOf(false) }
    LaunchedEffect(burst) { if (burst) { delay(700); burst = false } }
    val gestures = Modifier.pointerInput(repo.id) {
        detectTapGestures(onTap = { onOpen() }, onDoubleTap = { burst = true; onDoubleTap() })
    }
    val ratio = if (preview != null && images.isEmpty()) 2f else 1f
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            preview == null -> Box(Modifier.fillMaxSize().shimmer())
            images.isEmpty() -> OgImage(repo, Modifier.fillMaxSize().then(gestures))
            else -> {
                val count by rememberUpdatedState(images.size)
                val pager = rememberPagerState { count }
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { images.getOrElse(it) { "$it" } }) { page ->
                    val url = images.getOrNull(page) ?: return@HorizontalPager
                    var loading by remember(url) { mutableStateOf(true) }
                    Box(Modifier.fillMaxSize()) {
                        if (loading) Box(Modifier.fillMaxSize().shimmer())
                        AsyncImage(
                            // direct(): los previews guardados antes de este cambio aún traen github.com/…/raw
                            model = com.david.gitly.core.ReadmeParser.direct(url),
                            contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().then(gestures),
                            onSuccess = { loading = false },
                            onError = { st ->
                                loading = false
                                // Quitarla solo si de verdad no existe o no es una imagen; un fallo de red
                                // (ya reintentado) no debe borrarla del carrusel para siempre.
                                val t = st.result.throwable
                                val gone = if (t is coil.network.HttpException) t.response.code in 400..499 else t !is java.io.IOException
                                if (gone) vm.dropImage(repo, url)
                            },
                        )
                    }
                }
                if (images.size > 1) {
                    Surface(
                        shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    ) {
                        Text("${pager.currentPage + 1}/${images.size}", color = Color.White,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(images.size) { i ->
                            Box(Modifier.size(6.dp).clip(CircleShape).background(
                                if (i == pager.currentPage) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                            ))
                        }
                    }
                }
            }
        }
        AnimatedVisibility(burst, enter = scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(), exit = fadeOut()) {
            Icon(Icons.Filled.Star, null, tint = StarColor, modifier = Modifier.size(112.dp))
        }
    }
}

/** Texto del README bajo la imagen, plegado a 3 líneas con botón de expandir. */
@Composable
private fun Caption(repo: GhRepo, preview: ReadmePreview?, open: (Route) -> Unit) {
    val k = repo.fullName.lowercase()
    val caption = preview?.text?.takeIf { it.isNotBlank() } ?: repo.description.orEmpty()
    var expanded by rememberSaveable(k) { mutableStateOf(false) }
    var overflows by remember(k, caption) { mutableStateOf(false) }
    if (caption.isNotBlank()) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(repo.name) }
                append("  ")
                // Plegado: texto corrido para aprovechar las 3 líneas; expandido: con sus párrafos.
                append(if (expanded) caption else caption.replace(Regex("\\s*\\n+\\s*"), "  "))
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).animateContentSize(),
        )
    }
    Row(Modifier.padding(horizontal = 4.dp)) {
        if (overflows || expanded) TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
        }
        if (expanded && caption.endsWith("…")) TextButton(onClick = { open(Route.Repo(repo.fullName)) }) {
            Text(stringResource(R.string.full_readme))
        }
    }
    if (repo.topics.isNotEmpty()) {
        Text(
            repo.topics.take(6).joinToString("  ") { "#$it" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}
