package com.david.gitly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.david.gitly.AppViewModel
import com.david.gitly.R
import com.david.gitly.Route
import com.david.gitly.core.Features
import com.david.gitly.core.GhOwner
import com.david.gitly.core.GhRepo
import com.david.gitly.core.GhUser
import kotlinx.coroutines.launch

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** Perfil estilo Instagram. Sirve para el propio (con ajustes) y para el de cualquier otro usuario. */
@Composable
fun ProfileScreen(vm: AppViewModel, login: String, open: (Route) -> Unit, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSelf = login.equals(vm.account?.login, true)
    var user by remember(login) { mutableStateOf<GhUser?>(null) }
    var error by remember(login) { mutableStateOf<String?>(null) }
    var repos by remember(login) { mutableStateOf<List<GhRepo>?>(null) }
    var stars by remember(login) { mutableStateOf<List<GhRepo>?>(null) }
    var following by remember(login) { mutableStateOf<Boolean?>(null) }
    // Reposts de otra persona: null = cargando; vacío + repostsHidden = no usa Gitly / no los comparte contigo
    var otherReposts by remember(login) { mutableStateOf<List<GhRepo>?>(null) }
    var repostsHidden by remember(login) { mutableStateOf(false) }
    var tab by rememberSaveable(login) { mutableIntStateOf(0) }

    LaunchedEffect(login, vm.me) {
        try { user = vm.user(login) } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(login) { repos = runCatching { vm.userRepos(login) }.getOrDefault(emptyList()) }
    LaunchedEffect(login, tab) { if (tab == 1 && stars == null) stars = runCatching { vm.userStarred(login) }.getOrDefault(emptyList()) }
    LaunchedEffect(login) { if (!isSelf) following = vm.isFollowing(login) }
    LaunchedEffect(login, tab) {
        if (!isSelf && tab == 2 && otherReposts == null) {
            val r = vm.userReposts(login)
            repostsHidden = r == null
            otherReposts = r.orEmpty().map { it.repo.toGhRepo() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(login, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
            actions = {
                if (isSelf) IconButton(onClick = { open(Route.Settings) }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
                else {
                    IconButton(onClick = { openUrl(ctx, "https://github.com/$login") }) {
                        Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.open_in_github))
                    }
                    PeerMenuButton(vm, login)
                }
            },
        )
        val ownState = vm.profileGridState
        val otherState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LazyVerticalGrid(
            state = if (isSelf && onBack == null) ownState else otherState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val u = user
            if (u == null) {
                full {
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                return@LazyVerticalGrid
            }
            full { ProfileHeader(u, open) }
            full {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSelf) {
                        FilledTonalButton(onClick = { open(Route.EditProfile) }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.edit_profile))
                        }
                    } else {
                        val f = following == true
                        if (vm.messagingEnabled) FilledTonalButton(onClick = { open(Route.Chat(login)) }, Modifier.weight(1f)) {
                            Text(stringResource(R.string.message_btn))
                        }
                        val toggle: () -> Unit = { scope.launch { following = vm.setFollow(login, !f) } }
                        if (f) FilledTonalButton(onClick = toggle, Modifier.weight(1f), enabled = following != null) { Text(stringResource(R.string.following_btn)) }
                        else Button(onClick = toggle, Modifier.weight(1f), enabled = following != null) { Text(stringResource(R.string.follow_btn)) }
                    }
                    if (isSelf) FilledTonalButton(onClick = { shareUser(ctx, u.login) }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_profile))
                    } else androidx.compose.material3.FilledTonalIconButton(onClick = { shareUser(ctx, u.login) }) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.share_profile))
                    }
                }
            }
            full {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    ProfileTab(tab == 0, Icons.Outlined.GridView, stringResource(R.string.tab_repos)) { tab = 0 }
                    ProfileTab(tab == 1, Icons.Outlined.StarBorder, stringResource(R.string.tab_stars)) { tab = 1 }
                    ProfileTab(tab == 2, Icons.Outlined.Repeat, stringResource(R.string.tab_reposts)) { tab = 2 }
                    if (isSelf) ProfileTab(tab == 3, Icons.Outlined.Insights, stringResource(R.string.tab_taste)) { tab = 3 }
                }
            }
            when (tab) {
                0, 1, 2 -> {
                    val list = when (tab) {
                        0 -> repos
                        1 -> stars
                        // Reposts: los míos en vivo; los de otros, según lo que su privacidad me deja ver
                        else -> if (isSelf) vm.chats.myReposts.values.sortedByDescending { it.ts }.map { it.repo.toGhRepo() }
                        else otherReposts
                    }
                    when {
                        list == null -> full { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        list.isEmpty() -> full {
                            Text(when {
                                tab == 0 -> stringResource(R.string.no_repos)
                                tab == 1 -> stringResource(R.string.no_stars)
                                isSelf -> stringResource(R.string.no_reposts)
                                repostsHidden -> stringResource(R.string.reposts_hidden, login)
                                else -> stringResource(R.string.reposts_none_other, login)
                            },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(24.dp))
                        }
                        else -> items(list, key = { it.id }) { r -> RepoTile(r) { open(Route.Repo(r.fullName)) } }
                    }
                }
                else -> full { TasteSection(vm) }
            }
        }
    }
}

@Composable
private fun ProfileTab(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    Tab(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, text = { Text(label) })
}

@Composable
private fun ProfileHeader(u: GhUser, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.avatarUrl, 88)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(u.publicRepos, stringResource(R.string.stat_repos)) {}
                Stat(u.followers, stringResource(R.string.stat_followers)) { open(Route.People(u.login, followers = true)) }
                Stat(u.following, stringResource(R.string.stat_following)) { open(Route.People(u.login, followers = false)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(u.name ?: u.login, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        u.bio?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        u.company?.takeIf { it.isNotBlank() }?.let { InfoLine(Icons.Outlined.Business, it) }
        u.location?.takeIf { it.isNotBlank() }?.let { InfoLine(Icons.Outlined.Place, it) }
        u.blog?.takeIf { it.isNotBlank() }?.let { blog ->
            InfoLine(Icons.Outlined.Link, blog, link = true) { openUrl(ctx, if (blog.startsWith("http")) blog else "https://$blog") }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(compact(n), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, link: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (link) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Lo que el algoritmo ha aprendido (solo en el perfil propio). */
@Composable
private fun TasteSection(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(stringResource(R.string.taste_desc), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val top = vm.profile?.top(15).orEmpty()
        if (top.isEmpty()) Text(stringResource(R.string.taste_empty), style = MaterialTheme.typography.bodySmall)
        val max = top.firstOrNull()?.second ?: 1.0
        top.forEach { (k, v) ->
            val label = when (k.substringBefore(':')) {
                "t" -> "#${Features.label(k)}"
                "l" -> stringResource(R.string.taste_language, Features.label(k))
                else -> Features.label(k)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(160.dp))
                LinearProgressIndicator(progress = { (v / max).toFloat() }, modifier = Modifier.weight(1f).height(6.dp),
                    strokeCap = StrokeCap.Round)
            }
        }
    }
}

/** Seguidores / Siguiendo de un usuario. */
@Composable
fun PeopleScreen(vm: AppViewModel, login: String, followers: Boolean, open: (Route) -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(if (followers) 0 else 1) }
    val lists = remember(login) { mutableStateMapOf<Int, List<GhOwner>>() }
    LaunchedEffect(tab) { if (tab !in lists) lists[tab] = runCatching { vm.people(login, tab == 0) }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(login, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.stat_followers)) })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.stat_following)) })
        }
        val l = lists[tab]
        when {
            l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            l.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.nobody_here), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn {
                items(l, key = { it.login }) { p ->
                    ListItem(
                        leadingContent = { Avatar(p.avatarUrl, 44) },
                        headlineContent = { Text(p.login, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.clickable { open(Route.User(p.login)) },
                    )
                }
            }
        }
    }
}

/** Repo compartido → GhRepo mínimo para las casillas (id negativo estable, no choca con los de GitHub). */
fun com.david.gitly.chat.SharedRepo.toGhRepo(): GhRepo {
    val owner = fullName.substringBefore('/')
    return GhRepo(-(fullName.lowercase().hashCode().toLong() and 0xffffffffL) - 1, fullName, fullName.substringAfter('/'),
        com.david.gitly.core.GhOwner(owner, "https://github.com/$owner.png"), description, language, stars = stars)
}
