package com.david.gitly.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Block
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.david.gitly.AppViewModel
import com.david.gitly.Audience
import com.david.gitly.R
import com.david.gitly.Route
import com.david.gitly.ThemeMode

@Composable
fun SettingsScreen(vm: AppViewModel, open: (Route) -> Unit, onBack: () -> Unit, onLanguageChanged: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    val acc = vm.account
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // ---- Cuentas
            item { SectionTitle(R.string.settings_accounts) }
            items(vm.state.accounts, key = { it.login }) { a ->
                val active = a.login == acc?.login
                ListItem(
                    leadingContent = { Avatar(a.avatarUrl, 40) },
                    headlineContent = { Text("@${a.login}", fontWeight = if (active) FontWeight.SemiBold else null) },
                    supportingContent = if (active) ({ Text(stringResource(R.string.account_active)) }) else null,
                    trailingContent = if (active) ({ Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    modifier = Modifier.clickable { vm.switchAccount(a.login) },
                )
            }
            item { IconItem(Icons.Outlined.PersonAdd, stringResource(R.string.add_account)) { open(Route.AddAccount) } }

            // ---- Apariencia
            item { SectionTitle(R.string.settings_appearance) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp))
                    val modes = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = s.theme == m,
                                onClick = { vm.updateSettings { it.copy(theme = m) } },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
            }
            item {
                val supported = Build.VERSION.SDK_INT >= 31
                SwitchItem(
                    Icons.Outlined.Palette, stringResource(R.string.dynamic_color),
                    stringResource(if (supported) R.string.dynamic_color_desc else R.string.dynamic_color_unsupported),
                    checked = s.dynamicColor && supported, enabled = supported,
                ) { c -> vm.updateSettings { it.copy(dynamicColor = c) } }
            }

            // ---- Idioma
            item { SectionTitle(R.string.settings_language) }
            items(listOf("" to R.string.lang_system, "es" to R.string.lang_es, "en" to R.string.lang_en)) { (tag, label) ->
                ListItem(
                    leadingContent = { RadioButton(selected = s.language == tag, onClick = null) },
                    headlineContent = { Text(stringResource(label)) },
                    modifier = Modifier.clickable {
                        if (s.language != tag) { vm.updateSettings { it.copy(language = tag) }; onLanguageChanged() }
                    },
                )
            }

            // ---- Mensajes
            item { SectionTitle(R.string.settings_messages) }
            item {
                if (vm.messagingEnabled) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    IconItem(Icons.Outlined.Key, stringResource(R.string.dm_key_export), stringResource(R.string.dm_key_export_desc)) {
                        vm.exportMessagingKey()?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)); vm.message = com.david.gitly.UiText.Res(R.string.msg_key_copied) }
                    }
                } else {
                    IconItem(Icons.Outlined.Lock, stringResource(R.string.dm_enable_title), stringResource(R.string.dm_off_status)) { vm.enableMessaging() }
                }
            }
            item {
                var importing by remember { mutableStateOf(false) }
                IconItem(Icons.Outlined.Download, stringResource(R.string.dm_key_import), stringResource(R.string.dm_key_import_desc)) { importing = true }
                if (importing) {
                    var secret by remember { mutableStateOf("") }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { importing = false },
                        title = { Text(stringResource(R.string.dm_key_import)) },
                        text = {
                            androidx.compose.material3.OutlinedTextField(secret, { secret = it }, singleLine = true,
                                placeholder = { Text("nsec1…") },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(enabled = secret.isNotBlank(), onClick = {
                                vm.importMessagingKey(secret); importing = false
                            }) { Text(stringResource(R.string.dm_key_import)) }
                        },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { importing = false }) { Text(stringResource(R.string.cancel)) } },
                    )
                }
            }

            // ---- Notificaciones
            if (vm.messagingEnabled) {
                item { SectionTitle(R.string.settings_notifications) }
                item { NotificationSettings(vm) }
            }

            // ---- Privacidad
            item {
                SectionTitle(R.string.settings_privacy)
                Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                AudienceItem(
                    Icons.Outlined.Mail, stringResource(R.string.privacy_messages), s.privacy.messagesFrom,
                    mapOf(Audience.FOLLOWING to R.string.audience_mutuals, Audience.NOBODY to R.string.audience_nobody),
                ) { v -> vm.updateSettings { it.copy(privacy = it.privacy.copy(messagesFrom = v)) } }
            }
            item {
                AudienceItem(
                    Icons.Outlined.Repeat, stringResource(R.string.privacy_reposts), s.privacy.repostsVisibleTo,
                    mapOf(Audience.EVERYONE to R.string.audience_public, Audience.FOLLOWING to R.string.audience_mutuals, Audience.NOBODY to R.string.audience_only_me),
                ) { v -> vm.updateSettings { it.copy(privacy = it.privacy.copy(repostsVisibleTo = v)) } }
            }
            item {
                var showBlocked by remember { mutableStateOf(false) }
                if (vm.messagingEnabled) {
                    IconItem(Icons.Outlined.Block, stringResource(R.string.blocked_users),
                        pluralStringResource(R.plurals.blocked_count, vm.chats.blocked.size, vm.chats.blocked.size)) { showBlocked = true }
                }
                if (showBlocked) BlockedListDialog(vm) { showBlocked = false }
                SwitchItem(Icons.Outlined.DoneAll, stringResource(R.string.privacy_read_receipts), stringResource(R.string.privacy_read_receipts_desc),
                    s.privacy.readReceipts) { c -> vm.updateSettings { it.copy(privacy = it.privacy.copy(readReceipts = c)) } }
            }
            item {
                SwitchItem(Icons.Outlined.Visibility, stringResource(R.string.privacy_activity), stringResource(R.string.privacy_activity_desc),
                    s.privacy.shareActivity) { c -> vm.updateSettings { it.copy(privacy = it.privacy.copy(shareActivity = c)) } }
            }

            // ---- Algoritmo
            item { SectionTitle(R.string.settings_algorithm) }
            item {
                IconItem(Icons.Outlined.ThumbDown, stringResource(R.string.forget_dislikes),
                    stringResource(R.string.dislikes_count, acc?.hidden?.size ?: 0)) { vm.resetDislikes() }
            }
            item { IconItem(Icons.Outlined.History, stringResource(R.string.reset_history), stringResource(R.string.reset_history_desc)) { vm.resetHistory() } }

            // ---- Sesión
            item { SectionTitle(R.string.settings_session) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.logout_of, acc?.login ?: ""), color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { vm.logout(); onBack() },
                )
            }
            item {
                Text(
                    stringResource(R.string.about_line, version) +
                        (if (vm.rateRemaining >= 0) " · " + stringResource(R.string.api_remaining, vm.rateRemaining) else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun LeadingIcon(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun IconItem(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { LeadingIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        leadingContent = { LeadingIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun AudienceItem(icon: ImageVector, title: String, value: Audience, labels: Map<Audience, Int>, onChange: (Audience) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            leadingContent = { LeadingIcon(icon) },
            headlineContent = { Text(title) },
            supportingContent = { Text(stringResource(labels.getValue(value)), color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEach { (a, res) ->
                DropdownMenuItem(
                    text = { Text(stringResource(res)) },
                    leadingIcon = { RadioButton(selected = a == value, onClick = null) },
                    onClick = { expanded = false; onChange(a) },
                )
            }
        }
    }
}

@Composable
private fun NotificationSettings(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val push = com.david.gitly.push.Push
    // Releer al volver de los ajustes del sistema o de ntfy.
    var tick by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val status = vm.pushStatus + tick
    val allowed = remember(status) { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val active = remember(status) { push.active(ctx) }
    val dists = remember(status) { push.distributors(ctx) }
    val s = vm.settings

    Column {
        if (!allowed) {
            IconItem(Icons.Outlined.NotificationsOff, stringResource(R.string.notif_messages), stringResource(R.string.notif_blocked)) {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        } else {
            SwitchItem(Icons.Outlined.Notifications, stringResource(R.string.notif_messages), stringResource(R.string.notif_messages_desc),
                s.notifyMessages) { c -> vm.updateSettings { it.copy(notifyMessages = c) } }
        }
        val label = active?.let { pkg ->
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        when {
            active != null -> {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconItem(Icons.Outlined.Bolt, stringResource(R.string.notif_instant), stringResource(R.string.notif_instant_on, label ?: "")) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.notif_instant_disable)) }, onClick = {
                            menu = false; push.unregister(ctx); tick++
                        })
                    }
                }
            }
            dists.isNotEmpty() -> IconItem(Icons.Outlined.Bolt, stringResource(R.string.notif_instant), stringResource(R.string.notif_instant_off)) {
                (ctx as? android.app.Activity)?.let { push.register(it) { vm.refreshPushStatus() } }
            }
            else -> IconItem(Icons.Outlined.Schedule, stringResource(R.string.notif_instant), stringResource(R.string.notif_instant_none)) {
                openUrl(ctx, "https://ntfy.sh/docs/subscribe/phone/")
            }
        }
    }
}
