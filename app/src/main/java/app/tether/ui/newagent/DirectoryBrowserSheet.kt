package app.tether.ui.newagent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.LocalAppContainer
import app.tether.core.DirEntry
import app.tether.core.DirListing
import app.tether.ui.components.Hairline
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.prettyPath
import app.tether.ui.components.projectName
import app.tether.ui.components.rememberHaptics
import app.tether.ui.home.ErrorCard
import app.tether.ui.home.IconTile
import app.tether.ui.home.Loadable
import app.tether.ui.home.MiniBadge
import app.tether.ui.home.SkeletonListRow
import app.tether.ui.home.attempt
import app.tether.ui.home.humanMessage
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch

private data class Crumb(val label: String, val path: String)

private fun crumbsFor(path: String, home: String?): List<Crumb> {
    val homeRoot = home?.takeIf { it.length > 1 && (path == it || path.startsWith("$it/")) }
        ?: Regex("^/(home|Users)/[^/]+").find(path)?.value
    val out = mutableListOf<Crumb>()
    val rest: String
    if (homeRoot != null && (path == homeRoot || path.startsWith("$homeRoot/"))) {
        out += Crumb("~", homeRoot)
        rest = path.removePrefix(homeRoot)
    } else {
        out += Crumb("/", "/")
        rest = path
    }
    var acc = out.last().path.trimEnd('/')
    rest.split('/').filter { it.isNotEmpty() }.forEach { seg ->
        acc = "$acc/$seg"
        out += Crumb(seg, acc)
    }
    return out
}

/**
 * Remote folder picker over `ClaudeRemote.listDir`: breadcrumb, folders first, git repos badged,
 * up / home, quick filter for long directories, and a sticky "Use this folder".
 */
@Composable
fun DirectoryBrowserSheet(
    connectionId: String,
    machineName: String,
    startPath: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val remote = LocalAppContainer.current.remote
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var path by remember { mutableStateOf(startPath) }
    var reload by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<Loadable<DirListing>>(Loadable.Loading) }
    var lastGood by remember { mutableStateOf<DirListing?>(null) }
    var home by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(connectionId, path, reload) {
        listing = Loadable.Loading
        filter = ""
        val requested = path
        listing = attempt { remote.listDir(connectionId, requested) }.fold(
            onSuccess = { l ->
                if (requested == null) home = l.path
                lastGood = l
                Loadable.Ready(l)
            },
            onFailure = { Loadable.Failed(it.humanMessage()) },
        )
    }

    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            // Title row
            Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Choose a folder", style = MaterialTheme.typography.headlineSmall)
                    Text("on $machineName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { haptics.tick(); path = null }) {
                    Icon(Icons.Rounded.Home, contentDescription = "Home folder")
                }
                IconButton(onClick = { close(onDismiss) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            // Breadcrumb
            val shownPath = (listing as? Loadable.Ready)?.value?.path ?: path ?: lastGood?.path
            val crumbs = remember(shownPath, home) { shownPath?.let { crumbsFor(it, home) } ?: listOf(Crumb("~", "")) }
            val crumbState = rememberLazyListState()
            LaunchedEffect(crumbs.size) { if (crumbs.isNotEmpty()) crumbState.animateScrollToItem(crumbs.lastIndex) }
            LazyRow(
                state = crumbState,
                contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(crumbs, key = { i, c -> "$i:${c.path}" }) { i, c ->
                    val last = i == crumbs.lastIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(16.dp))
                        Text(
                            c.label,
                            style = TetherTheme.type.mono.copy(fontWeight = if (last) FontWeight.Medium else FontWeight.Normal),
                            color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .then(if (!last && c.path.isNotEmpty()) Modifier.clickable { haptics.tick(); path = c.path } else Modifier)
                                .background(if (last) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            val ready = (listing as? Loadable.Ready)?.value
            if (ready != null && ready.entries.size > 14) {
                FilterField(filter, { filter = it }, Modifier.padding(horizontal = Space.gutter, vertical = Space.xs))
            }
            Hairline()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = listing,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "dir",
                ) { l ->
                    when (l) {
                        Loadable.Loading -> Column(Modifier.fillMaxSize()) { repeat(7) { SkeletonListRow(Modifier.padding(horizontal = Space.xs)) } }
                        is Loadable.Failed -> Column(Modifier.fillMaxSize().padding(Space.gutter)) {
                            ErrorCard(
                                title = "Couldn't open this folder",
                                message = l.message,
                                onRetry = { reload++ },
                            )
                            if (path != null) {
                                Spacer(Modifier.height(Space.md))
                                SecondaryButton("Go to home folder", onClick = { path = null }, icon = Icons.Rounded.Home, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        is Loadable.Ready -> DirEntries(
                            listing = l.value,
                            filter = filter,
                            onOpen = { e -> haptics.tick(); path = e.path },
                        )
                    }
                }
            }

            Hairline()
            val parent = ready?.parent
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(
                    "Up",
                    onClick = { if (parent != null) path = parent },
                    enabled = parent != null,
                    icon = Icons.Rounded.ArrowUpward,
                )
                PrimaryButton(
                    if (ready != null) "Use ${projectName(ready.path).let { if (it.length > 18) "this folder" else "“$it”" }}" else "Use this folder",
                    onClick = {
                        val chosen = ready?.path ?: return@PrimaryButton
                        haptics.confirm()
                        close { onPick(chosen) }
                    },
                    enabled = ready != null,
                    icon = Icons.Rounded.FolderOpen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DirEntries(listing: DirListing, filter: String, onOpen: (DirEntry) -> Unit) {
    val entries = remember(listing, filter) {
        val q = filter.trim()
        listing.entries
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy<DirEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }
    val listState = rememberLazyListState()
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(Space.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            IconTile(Icons.Rounded.FolderOpen, TetherTheme.colors.faint, size = 48.dp)
            Spacer(Modifier.height(Space.md))
            Text(
                if (filter.isBlank()) "This folder is empty" else "Nothing matches “$filter”",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filter.isBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(prettyPath(listing.path), style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint)
            }
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = Space.xs)) {
        items(entries, key = { it.path }) { e ->
            if (e.isDir) {
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(onClickLabel = "Open ${e.name}") { onOpen(e) }
                        .padding(horizontal = Space.gutter, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(Icons.Rounded.Folder, if (e.isGitRepo) TetherTheme.colors.clay else TetherTheme.colors.info, size = 34.dp)
                    Spacer(Modifier.width(14.dp))
                    // Name + badge take all free space (a second weighted spacer would cap the name at half).
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (e.isGitRepo) {
                            Spacer(Modifier.width(8.dp))
                            MiniBadge("git", TetherTheme.colors.clay, mono = true)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(20.dp))
                }
            } else {
                Row(
                    Modifier.animateItem().fillMaxWidth().padding(horizontal = Space.gutter, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(e.name, style = TetherTheme.type.mono, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, TetherTheme.colors.hairline, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Filter", style = MaterialTheme.typography.bodyMedium, color = TetherTheme.colors.faint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(TetherTheme.colors.clay),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
            }
        }
    }
}
