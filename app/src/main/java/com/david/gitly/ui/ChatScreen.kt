package com.david.gitly.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.david.gitly.AppViewModel
import com.david.gitly.Friend
import com.david.gitly.R
import com.david.gitly.Route
import com.david.gitly.chat.ChatAvailability
import com.david.gitly.chat.ChatMsg
import com.david.gitly.chat.MsgStatus
import com.david.gitly.chat.SharedRepo
import com.david.gitly.core.GhOwner
import com.david.gitly.core.GhRepo
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun avatarOf(login: String) = "https://github.com/$login.png"
private fun iso(ts: Long) = Instant.ofEpochSecond(ts).toString()

// ---- Pestaña Amigos ----------------------------------------------------------------------------

@Composable
fun FriendsScreen(vm: AppViewModel, open: (Route) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(vm.messagingEnabled) {
        if (vm.messagingEnabled) {
            vm.loadMutuals()
            while (true) { vm.syncChats(); delay(15_000) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.tab_friends), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(tab == 0, onClick = { tab = 0 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.friends_chats))
                    if (vm.unreadTotal > 0) { Spacer(Modifier.width(6.dp)); Badge { Text("${vm.unreadTotal}") } }
                }
            })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.friends_activity)) })
        }
        if (tab == 0) ChatsPane(vm, open) else ActivityPane(vm, open)
    }
}

@Composable
private fun ChatsPane(vm: AppViewModel, open: (Route) -> Unit) {
    val ctx = LocalContext.current
    if (!vm.messagingEnabled) { EnableMessagingCard(vm); return }
    val chats = vm.chats
    val friends = vm.mutuals
    val threads = chats.threads.entries.filter { it.value.isNotEmpty() }.sortedByDescending { it.value.last().ts }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionLabel(stringResource(R.string.dm_mutuals)) }
        item {
            when {
                friends == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                friends.isEmpty() -> Hint(stringResource(R.string.dm_no_mutuals))
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(friends, key = { it.login }) { f ->
                        FriendBubble(f) {
                            if (f.hasGitly == false) share(ctx, ctx.getString(R.string.dm_invite_text))
                            else open(Route.Chat(f.login))
                        }
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.friends_chats)) }
        if (threads.isEmpty()) item { Hint(stringResource(R.string.dm_no_chats)) }
        items(threads, key = { it.key }) { (peer, msgs) ->
            val last = msgs.last()
            val unread = chats.unread(peer)
            val login = friends?.firstOrNull { it.login.equals(peer, true) }?.login ?: peer
            ListItem(
                leadingContent = { Avatar(avatarOf(login), 52) },
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(login, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium)
                        if (chats.isMuted(peer)) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Outlined.NotificationsOff, stringResource(R.string.peer_muted), Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                supportingContent = {
                    if (chats.isBlocked(peer)) Text(stringResource(R.string.peer_blocked_label), color = MaterialTheme.colorScheme.error)
                    else Text(preview(last), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (unread > 0) FontWeight.SemiBold else null)
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(relativeTime(iso(last.ts)), style = MaterialTheme.typography.labelSmall)
                        if (unread > 0 && !chats.isBlocked(peer)) {
                            Spacer(Modifier.height(4.dp))
                            if (chats.isMuted(peer)) Badge(containerColor = MaterialTheme.colorScheme.outline) { Text("$unread") }
                            else Badge { Text("$unread") }
                        }
                    }
                },
                modifier = Modifier.clickable { open(Route.Chat(login)) },
            )
        }
    }
}

@Composable
private fun preview(m: ChatMsg): String {
    val body = m.repo?.let { "📦 ${it.fullName}" + if (m.text.isNotBlank()) " · ${m.text}" else "" } ?: m.text
    return if (m.mine) stringResource(R.string.dm_you_prefix, body) else body
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun FriendBubble(f: Friend, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(onClick = onClick).alpha(if (f.hasGitly == false) 0.55f else 1f),
    ) {
        val ring = if (f.hasGitly == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        Box(Modifier.border(2.dp, ring, CircleShape).padding(4.dp)) { Avatar(f.avatarUrl, 56) }
        Spacer(Modifier.height(4.dp))
        Text(f.login, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (f.hasGitly == false) Text(stringResource(R.string.dm_invite), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EnableMessagingCard(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text(stringResource(R.string.dm_enable_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.dm_enable_text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Button(onClick = vm::enableMessaging, enabled = !vm.enablingMessaging, modifier = Modifier.fillMaxWidth()) {
                    if (vm.enablingMessaging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.dm_enable_btn))
                }
            }
        }
    }
}

@Composable
private fun ActivityPane(vm: AppViewModel, open: (Route) -> Unit) {
    LaunchedEffect(Unit) { if (vm.activity.isEmpty()) vm.loadActivity() }
    PullToRefreshBox(isRefreshing = vm.activityLoading, onRefresh = vm::loadActivity, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.activity.isEmpty() && !vm.activityLoading) item { Hint(stringResource(R.string.friends_empty)) }
            items(vm.activity) { a ->
                ListItem(
                    leadingContent = { Avatar(a.actor.avatarUrl, 40) },
                    headlineContent = {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("@${a.actor.login}") }
                            append(" ")
                            append(activityVerb(a.kind))
                        })
                    },
                    supportingContent = { Text(a.repoFullName, color = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Text(relativeTime(a.at), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.clickable { open(Route.Repo(a.repoFullName)) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun activityVerb(kind: String): String = stringResource(
    when (kind) {
        "WatchEvent" -> R.string.act_starred
        "ForkEvent" -> R.string.act_forked
        "PublicEvent" -> R.string.act_published
        "ReleaseEvent" -> R.string.act_released
        else -> R.string.act_created
    }
)

// ---- Conversación ------------------------------------------------------------------------------

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ChatScreen(vm: AppViewModel, login: String, open: (Route) -> Unit, onBack: () -> Unit) {
    val peer = login.lowercase()
    var avail by remember(login) { mutableStateOf(ChatAvailability.CHECKING) }
    var text by rememberSaveable(login) { mutableStateOf("") }
    val blocked = vm.isBlocked(login)
    LaunchedEffect(login, blocked) { avail = vm.chatAvailability(login) }
    LaunchedEffect(login) { while (true) { vm.syncChats(); delay(8_000) } }
    val msgs = vm.chats.threads[peer].orEmpty()
    LaunchedEffect(msgs.size) { vm.markRead(peer) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open(Route.User(login)) }) {
                    Avatar(avatarOf(login), 38)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(login, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(3.dp))
                            Text(stringResource(R.string.dm_e2e), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            actions = { PeerMenuButton(vm, login) },
        )
        // "Visto": solo si yo también comparto las mías (como WhatsApp) y el otro me lo ha dicho.
        val seenUpTo = if (vm.settings.privacy.readReceipts) vm.chats.peerReadUpTo[peer] else null
        val lastMine = msgs.lastOrNull()?.takeIf { it.mine }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(msgs.asReversed(), key = { it.id }) { m ->
                val seen = m.mine && seenUpTo != null && m.ts <= seenUpTo
                Bubble(m, seen, showSeenLabel = seen && m.id == lastMine?.id,
                    onRepo = { open(Route.Repo(it)) }, onRetry = { vm.retryMessage(m) })
            }
            if (msgs.isEmpty()) item { Hint(stringResource(R.string.dm_empty_thread)) }
        }
        when (avail) {
            ChatAvailability.OK -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.dm_input_hint)) },
                    shape = RoundedCornerShape(24.dp), maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { vm.sendMessage(login, text.trim()); text = "" }, enabled = text.isNotBlank(),
                    modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.dm_send))
                }
            }
            ChatAvailability.CHECKING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ChatAvailability.BLOCKED -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.peer_blocked_banner, login), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    TextButton(onClick = { vm.unblock(login) }) { Text(stringResource(R.string.peer_unblock)) }
                }
            }
            else -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (avail) {
                        ChatAvailability.NOT_MUTUAL -> stringResource(R.string.dm_not_mutual)
                        ChatAvailability.NO_GITLY -> stringResource(R.string.dm_not_gitly, login)
                        else -> stringResource(R.string.dm_disabled)
                    },
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, seen: Boolean, showSeenLabel: Boolean, onRepo: (String) -> Unit, onRetry: () -> Unit) {
    val mine = m.mine
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, if (mine) 4.dp else 20.dp, if (mine) 20.dp else 4.dp),
            color = bg,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(4.dp)) {
                m.repo?.let { RepoMini(it) { onRepo(it.fullName) } }
                if (m.text.isNotBlank()) Text(m.text, color = fg, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(Instant.ofEpochSecond(m.ts).atZone(ZoneId.systemDefault()).format(timeFmt),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mine) {
                Spacer(Modifier.width(4.dp))
                when (m.status) {
                    MsgStatus.SENDING -> Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    MsgStatus.SENT -> if (seen) Icon(Icons.Outlined.DoneAll, stringResource(R.string.dm_seen), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Outlined.Done, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    MsgStatus.FAILED -> TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.dm_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (showSeenLabel) Text(stringResource(R.string.dm_seen), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** Tarjeta de repo dentro de una burbuja. */
@Composable
private fun RepoMini(r: SharedRepo, onClick: () -> Unit) {
    val owner = r.fullName.substringBefore('/')
    val fake = GhRepo(0, r.fullName, r.fullName.substringAfter('/'), GhOwner(owner, avatarOf(owner)))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        onClick = onClick, modifier = Modifier.width(260.dp)) {
        Column {
            OgImage(fake, Modifier.fillMaxWidth().aspectRatio(2f))
            Column(Modifier.padding(10.dp)) {
                Text(r.fullName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = StarColor)
                    Spacer(Modifier.width(3.dp))
                    Text(compact(r.stars), style = MaterialTheme.typography.labelMedium)
                    r.language?.let { Spacer(Modifier.width(8.dp)); LanguageDot(it) }
                }
            }
        }
    }
}

// ---- Enviar un repo a un amigo -----------------------------------------------------------------

/** Hoja del botón ✈: amigos mutuos con Gitly; "Más opciones" abre el compartir de Android. */
@Composable
fun SendSheet(vm: AppViewModel, repo: GhRepo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadMutuals() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.send_to_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val friends = vm.mutuals?.filter { it.hasGitly != false }
            when {
                friends == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                friends.isEmpty() -> Hint(stringResource(R.string.dm_no_gitly_friends))
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(friends, key = { it.login }) { f ->
                        FriendBubble(f) { vm.sendRepo(f.login, repo); onDismiss() }
                    }
                }
            }
            TextButton(onClick = { onDismiss(); shareRepo(ctx, repo) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.send_more_options))
            }
        }
    }
}
