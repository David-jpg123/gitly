package com.david.gitly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.david.gitly.AppViewModel
import com.david.gitly.R
import kotlinx.coroutines.launch

/** Edición del perfil de GitHub sin salir de la app. La foto solo se puede cambiar en la web. */
@Composable
fun EditProfileScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = vm.me
    var name by rememberSaveable { mutableStateOf(me?.name.orEmpty()) }
    var bio by rememberSaveable { mutableStateOf(me?.bio.orEmpty()) }
    var company by rememberSaveable { mutableStateOf(me?.company.orEmpty()) }
    var location by rememberSaveable { mutableStateOf(me?.location.orEmpty()) }
    var blog by rememberSaveable { mutableStateOf(me?.blog.orEmpty()) }
    var twitter by rememberSaveable { mutableStateOf(me?.twitter.orEmpty()) }
    var saving by rememberSaveable { mutableStateOf(false) }

    val original = listOf(me?.name, me?.bio, me?.company, me?.location, me?.blog, me?.twitter).map { it.orEmpty() }
    val current = listOf(name, bio, company, location, blog, twitter)
    val changed = original != current

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text(stringResource(R.string.edit_profile)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) } },
            actions = {
                if (saving) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                else TextButton(
                    enabled = changed && bio.length <= 160,
                    onClick = {
                        saving = true
                        scope.launch {
                            val keys = listOf("name", "bio", "company", "location", "blog", "twitter_username")
                            val changes = keys.indices.filter { current[it] != original[it] }.associate { keys[it] to current[it].trim() }
                            val ok = vm.saveProfile(changes)
                            saving = false
                            if (ok) onBack()
                        }
                    },
                ) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(me?.avatarUrl.orEmpty(), 96)
                TextButton(onClick = { openUrl(ctx, "https://github.com/settings/profile") }) {
                    Text(stringResource(R.string.change_photo))
                }
                Text(stringResource(R.string.change_photo_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Field(Icons.Outlined.Person, R.string.field_name, name) { name = it }
            OutlinedTextField(
                value = bio, onValueChange = { bio = it },
                label = { Text(stringResource(R.string.field_bio)) },
                supportingText = { Text("${bio.length}/160") },
                isError = bio.length > 160,
                minLines = 3, maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Field(Icons.Outlined.Business, R.string.field_company, company) { company = it }
            Field(Icons.Outlined.Place, R.string.field_location, location) { location = it }
            Field(Icons.Outlined.Link, R.string.field_website, blog) { blog = it }
            Field(Icons.Outlined.AlternateEmail, R.string.field_twitter, twitter) { twitter = it.removePrefix("@") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(icon: ImageVector, label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
