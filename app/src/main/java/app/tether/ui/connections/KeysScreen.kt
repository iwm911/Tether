package app.tether.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.ui.components.EmptyState
import app.tether.ui.components.PageHeader
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.TagChip
import app.tether.ui.components.TetherCard
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.relativeTime
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

@Composable
fun KeysScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: KeysViewModel = viewModel { KeysViewModel(container) }
    val keyItems by vm.items.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TetherTopBar(title = if (atTop) "" else "SSH keys", onBack = onBack, modifier = Modifier.statusBarsPadding()) },
        snackbarHost = { TetherSnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (keyItems.isEmpty()) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            icon = Icons.Rounded.Key,
                            title = "No keys yet",
                            body = "Keys let Tether sign in without a password. Generate one for this phone, or bring a key you already use.",
                            actionLabel = "Generate a key",
                            onAction = vm::openGenerate,
                        )
                        TextButton(onClick = vm::openImport) {
                            Text("Import an existing key", color = TetherTheme.colors.clay, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Space.xxxl),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    item(key = "header") {
                        Column {
                            PageHeader(
                                title = "SSH keys",
                                eyebrow = "${keyItems.size} on this phone",
                                subtitle = "Private keys are encrypted with the Android Keystore and never leave this device.",
                            )
                            Spacer(Modifier.height(Space.md))
                            Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                PrimaryButton("Generate", onClick = vm::openGenerate, icon = Icons.Rounded.Add, modifier = Modifier.weight(1f))
                                SecondaryButton("Import", onClick = vm::openImport, icon = Icons.Rounded.FileOpen, modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(Space.sm))
                        }
                    }
                    items(keyItems, key = { it.key.id }) { item ->
                        KeyCard(item, onClick = { vm.openSheet(item.key.id) }, modifier = Modifier.animateItem().padding(horizontal = Space.gutter))
                    }
                    item(key = "footer") {
                        Text(
                            "Share a public key to add it to ~/.ssh/authorized_keys yourself, or use Install on server in a machine's settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TetherTheme.colors.faint,
                            modifier = Modifier.padding(horizontal = Space.gutter + Space.xs, vertical = Space.sm).navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    if (ui.generateOpen) {
        GenerateKeyDialog(generating = ui.generating, onGenerate = vm::generate, onDismiss = vm::closeGenerate)
    }
    if (ui.importOpen) {
        ImportKeySheet(importing = ui.importing, error = ui.importError, onImport = vm::importKey, onDismiss = vm::closeImport)
    }
    val sheetItem = keyItems.firstOrNull { it.key.id == ui.sheetKeyId }
    if (sheetItem != null) {
        KeyDetailSheet(
            item = sheetItem,
            reveal = ui.revealKeyId == sheetItem.key.id,
            onDelete = { vm.askDelete(sheetItem.key.id) },
            onDismiss = vm::closeSheet,
        )
    }
    val deleteItem = keyItems.firstOrNull { it.key.id == ui.confirmDeleteId }
    if (deleteItem != null) {
        val n = deleteItem.usedBy.size
        ConfirmDialog(
            title = "Delete “${deleteItem.key.name}”?",
            body = if (n == 0) "The private key is erased from this phone. This can't be undone."
            else "Used by $n machine${if (n == 1) "" else "s"}: ${deleteItem.usedBy.joinToString { it.name }}. " +
                "${if (n == 1) "It" else "They"} won't be able to sign in until you pick another key or a password.",
            confirmLabel = "Delete",
            danger = true,
            icon = if (n == 0) Icons.Rounded.DeleteOutline else Icons.Rounded.WarningAmber,
            onConfirm = { vm.delete(deleteItem.key.id) },
            onDismiss = vm::cancelDelete,
        )
    }
}

@Composable
private fun KeyCard(item: KeyItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TetherTheme.colors
    val k = item.key
    TetherCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(colors.clay.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Key, null, tint = colors.clay, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(k.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(k.fingerprint, style = TetherTheme.type.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagChip(prettyAlgorithm(k.algorithm))
                    if (k.imported) TagChip("Imported")
                    if (k.hasPassphrase) TagChip("Passphrase")
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    buildString {
                        append(createdLabel(k.createdAt))
                        append(" · ")
                        append(
                            when (item.usedBy.size) {
                                0 -> "Not used yet"
                                1 -> "Used by ${item.usedBy[0].name}"
                                else -> "Used by ${item.usedBy.size} machines"
                            }
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun createdLabel(epochMs: Long): String {
    val r = relativeTime(epochMs)
    return when {
        r == "now" -> "Created just now"
        r.last() == 'm' || r.last() == 'h' -> "Created $r ago"
        else -> "Created $r"
    }
}

@Composable
private fun KeyDetailSheet(item: KeyItem, reveal: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val colors = TetherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.xl),
        ) {
            Text(if (reveal) "NEW KEY READY" else "PUBLIC KEY", style = TetherTheme.type.eyebrow, color = colors.clay)
            Spacer(Modifier.height(6.dp))
            Text(item.key.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.xs))
            Text(
                "Add this line to ~/.ssh/authorized_keys on any machine you want to reach with this key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.lg))
            PublicKeyCard(item.key, reveal = reveal)
            Spacer(Modifier.height(Space.lg))
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                DetailRow("Algorithm", prettyAlgorithm(item.key.algorithm))
                DetailRow("Created", createdLabel(item.key.createdAt).removePrefix("Created ").replaceFirstChar { it.uppercase() })
                DetailRow("Origin", if (item.key.imported) "Imported" else "Generated on this phone")
                DetailRow("Passphrase", if (item.key.hasPassphrase) "Yes" else "None")
                DetailRow(
                    "Used by",
                    if (item.usedBy.isEmpty()) "No machines" else item.usedBy.joinToString { it.name },
                )
            }
            Spacer(Modifier.height(Space.xl))
            SecondaryButton(
                "Delete key",
                onClick = onDelete,
                icon = Icons.Rounded.DeleteOutline,
                contentColor = colors.danger,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.sm))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
