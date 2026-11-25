package com.david.gitly.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.david.gitly.AppViewModel
import com.david.gitly.R
import com.david.gitly.Route
import com.david.gitly.core.Features
import com.david.gitly.core.Scored
import java.time.LocalDate

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun ExploreScreen(vm: AppViewModel, open: (Route) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val trending = stringResource(R.string.preset_trending)
    val android = stringResource(R.string.preset_android)
    val presets = remember(vm.profile, trending, android) {
        val month = LocalDate.now().minusDays(30)
        val halfYear = LocalDate.now().minusDays(180)
        buildList {
            add(trending to "created:>$month stars:>50")
            add(android to "topic:android stars:>200 pushed:>$halfYear")
            vm.profile?.top(6, "t:")?.forEach { add("#${Features.label(it.first)}" to "topic:${Features.label(it.first)} stars:>20") }
            listOf("self-hosted", "privacy", "linux", "rust", "llm").forEach { t ->
                if (none { it.first == "#$t" }) add("#$t" to "topic:$t stars:>100")
            }
        }
    }
    LaunchedEffect(Unit) { if (vm.exploreTitle == null) presets.first().let { vm.explore(it.first, it.second) } }
    LaunchedEffect(vm.profile) { vm.loadGems() }

    LazyVerticalGrid(
        state = vm.exploreGridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        full {
            Text(stringResource(R.string.tab_explore), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        }
        full {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) vm.explore("“${query.trim()}”", query.trim(), sort = "best")
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Pequeñas joyas
        full {
            SectionHeader(Icons.Outlined.Diamond, stringResource(R.string.gems_title), stringResource(R.string.gems_subtitle)) {
                IconButton(onClick = { vm.loadGems(force = true) }, enabled = !vm.gemsLoading) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                }
            }
        }
        full {
            when {
                vm.gems.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(vm.gems, key = { it.repo.id }) { GemCard(vm, it, open) }
                }
                vm.gemsLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Text(stringResource(if (vm.profile == null) R.string.gems_need_feed else R.string.gems_none),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- Descubrir
        full { SectionHeader(Icons.Outlined.TravelExplore, stringResource(R.string.discover_title), null) }
        full {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { (title, q) ->
                    FilterChip(selected = vm.exploreTitle == title, onClick = { vm.explore(title, q) }, label = { Text(title) })
                }
            }
        }
        if (vm.exploreLoading) full { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(vm.exploreResults, key = { it.id }) { repo -> RepoTile(repo) { open(Route.Repo(repo.fullName)) } }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String?, action: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action()
    }
}

@Composable
private fun GemCard(vm: AppViewModel, s: Scored, open: (Route) -> Unit) {
    val repo = s.repo
    val k = repo.fullName.lowercase()
    LaunchedEffect(k) { vm.loadPreview(repo) }
    val img = vm.previews[k]?.images?.firstOrNull()
    ElevatedCard(
        onClick = { open(Route.Repo(repo.fullName)) },
        modifier = Modifier.width(264.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (img == null) OgImage(repo, Modifier.fillMaxSize())
            else AsyncImage(
                model = img, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { vm.dropImage(repo, img) },
            )
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(Icons.Outlined.Diamond, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(4.dp))
                    Text(reasonText(s.reason), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(repo.owner.login, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(repo.description.orEmpty(), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = StarColor)
                Spacer(Modifier.width(3.dp))
                Text(compact(repo.stars), style = MaterialTheme.typography.labelMedium)
                repo.language?.let { Spacer(Modifier.width(10.dp)); LanguageDot(it) }
            }
        }
    }
}
