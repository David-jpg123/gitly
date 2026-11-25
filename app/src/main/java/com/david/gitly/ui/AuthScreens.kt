package com.david.gitly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.david.gitly.AppViewModel
import com.david.gitly.R
import com.david.gitly.Route

// ---- Login / añadir cuenta -------------------------------------------------------------------

@Composable
fun LoginScreen(vm: AppViewModel, onCancel: (() -> Unit)?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(!vm.oauthAvailable) }
    val initial = remember { vm.account?.login }
    LaunchedEffect(vm.account?.login) { if (vm.account != null && vm.account?.login != initial) onDone() }
    val code = vm.deviceCode
    // En cuanto llega el código, al portapapeles: en GitHub solo hay que pegarlo.
    LaunchedEffect(code?.userCode) { code?.let { clipboard.setText(AnnotatedString(it.userCode)) } }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (onCancel != null) Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.cancelDeviceLogin(); onCancel() }) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
        }
        Spacer(Modifier.height(if (onCancel != null) 8.dp else 48.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Hub, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(if (onCancel != null) R.string.add_account else R.string.app_name),
            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_tagline), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        if (onCancel == null) {
            Feature(Icons.Outlined.AutoAwesome, R.string.login_feature_feed)
            Feature(Icons.Outlined.Diamond, R.string.login_feature_gems)
            Feature(Icons.Outlined.Android, R.string.login_feature_apk)
            Spacer(Modifier.height(28.dp))
        }

        if (vm.oauthAvailable) {
            when {
                vm.loggingIn -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.login_signing_in))
                }
                code != null -> DeviceCodeCard(code.userCode,
                    onCopy = { clipboard.setText(AnnotatedString(code.userCode)) },
                    onOpen = { openUrl(ctx, code.verificationUri) },
                    onCancel = vm::cancelDeviceLogin)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Principal: la página de login de GitHub (usuario, contraseña, 2FA) dentro de la app.
                    Button(
                        onClick = { vm.startWebLogin(ctx) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Login, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.login_with_github), style = MaterialTheme.typography.titleMedium)
                    }
                    // Alternativa sin navegador: código de dispositivo.
                    TextButton(onClick = vm::startDeviceLogin) { Text(stringResource(R.string.login_with_code)) }
                }
            }
            vm.deviceError?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            if (!showToken) vm.loginError?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showToken = !showToken }) {
                Text(stringResource(if (showToken) R.string.login_hide_token else R.string.login_use_token))
            }
        }

        AnimatedVisibility(showToken) {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Step(1, stringResource(R.string.login_token_step1))
                    FilledTonalButton(onClick = {
                        openUrl(ctx, "https://github.com/settings/tokens/new?description=Gitly&scopes=public_repo,user,gist")
                    }) { Text(stringResource(R.string.login_create_token)) }
                    Step(2, stringResource(R.string.login_token_step2))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.login_token)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank()) vm.login(token) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    vm.loginError?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { vm.login(token) },
                        enabled = token.isNotBlank() && !vm.loggingIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(if (vm.loggingIn) R.string.login_checking else R.string.login_enter)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Feature(icon: ImageVector, text: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Código del device flow: grande, ya copiado, y botón para ir a GitHub a pegarlo. */
@Composable
private fun DeviceCodeCard(userCode: String, onCopy: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Step(1, stringResource(R.string.login_step_copy))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, onClick = onCopy) {
                Text(
                    userCode, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            AssistChip(onClick = onCopy, label = { Text(stringResource(R.string.login_code_copied)) },
                leadingIcon = { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) })
            Step(2, stringResource(R.string.login_step_paste))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.OpenInBrowser, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.login_open_github))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.login_waiting), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
