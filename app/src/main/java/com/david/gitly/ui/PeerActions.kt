package com.david.gitly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.david.gitly.AppViewModel
import com.david.gitly.R

/** Menú ⋮ de una persona (chat y perfil): silenciar, ocultar reposts, bloquear, denunciar. */
@Composable
fun PeerMenuButton(vm: AppViewModel, login: String) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var askBlock by remember { mutableStateOf(false) }
    var askMute by remember { mutableStateOf(false) }
    val messaging = vm.messagingEnabled
    val blocked = vm.isBlocked(login)
    val muted = vm.isMuted(login)
    val repostsMuted = vm.repostsMuted(login)

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (messaging && !blocked) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (muted) R.string.peer_unmute else R.string.peer_mute)) },
                    leadingIcon = { Icon(if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, null) },
                    onClick = { open = false; if (muted) vm.mute(login, null, off = true) else askMute = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (repostsMuted) R.string.peer_show_reposts else R.string.peer_hide_reposts)) },
                    leadingIcon = { Icon(Icons.Outlined.Repeat, null) },
                    onClick = { open = false; vm.setRepostsMuted(login, !repostsMuted) },
                )
            }
            if (messaging) DropdownMenuItem(
                text = { Text(stringResource(if (blocked) R.string.peer_unblock else R.string.peer_block),
                    color = if (blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Block, null, tint = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) },
                onClick = { open = false; if (blocked) vm.unblock(login) else askBlock = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.peer_report)) },
                leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                onClick = { open = false; openUrl(ctx, "https://github.com/contact/report-abuse?report=$login") },
            )
        }
    }
    if (askBlock) BlockDialog(login, onDismiss = { askBlock = false }) { gh -> askBlock = false; vm.block(login, gh) }
    if (askMute) MuteDialog(login, onDismiss = { askMute = false }) { h -> askMute = false; vm.mute(login, h) }
}

@Composable
private fun BlockDialog(login: String, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var alsoGitHub by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Block, null) },
        title = { Text(stringResource(R.string.block_title, login)) },
        text = {
            Column {
                Text(stringResource(R.string.block_desc))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { alsoGitHub = !alsoGitHub }) {
                    Checkbox(alsoGitHub, { alsoGitHub = it })
                    Text(stringResource(R.string.block_github), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alsoGitHub) }) {
                Text(stringResource(R.string.peer_block), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MuteDialog(login: String, onDismiss: () -> Unit, onConfirm: (Int?) -> Unit) {
    val options = listOf<Pair<Int?, Int>>(1 to R.string.mute_1h, 8 to R.string.mute_8h, 24 * 7 to R.string.mute_week, null to R.string.mute_forever)
    var pick by remember { mutableStateOf(options[1].first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.NotificationsOff, null) },
        title = { Text(stringResource(R.string.mute_title, login)) },
        text = {
            Column {
                Text(stringResource(R.string.mute_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                options.forEach { (h, res) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { pick = h }) {
                        RadioButton(pick == h, { pick = h })
                        Text(stringResource(res))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pick) }) { Text(stringResource(R.string.peer_mute)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Lista de bloqueados con botón para desbloquear (Ajustes → Privacidad). */
@Composable
fun BlockedListDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val list = vm.chats.blocked
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blocked_users)) },
        text = {
            Column {
                if (list.isEmpty()) Text(stringResource(R.string.blocked_none))
                list.forEach { login ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Avatar("https://github.com/$login.png?size=80", 36)
                        Spacer(Modifier.width(12.dp))
                        Text(login, Modifier.weight(1f))
                        TextButton(onClick = { vm.unblock(login) }) { Text(stringResource(R.string.peer_unblock)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
