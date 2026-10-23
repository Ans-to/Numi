@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.calcvault.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calcvault.data.*
import com.calcvault.media.PrivateMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
private val previewSlots = Semaphore(2)

@Composable fun VaultScreen(model: VaultViewModel, state: AppState, onBiometric: () -> Unit, biometricAvailable: Boolean, onPickMedia: (String?) -> Unit, onPickFiles: (String?) -> Unit, onPickMove: (String?) -> Unit) {
    var category by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Newest") }
    var folder by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val viewing = state.catalog.items.find { it.id == state.viewingId }
    var dialog by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val filtered = remember(state.catalog, query, category, folder, sort) {
        val matches = state.catalog.items.filter { (category == "All" || category == "Folders" || it.category == category) &&
            (folder == null || it.folder == folder) && it.name.contains(query, true) }
        when (sort) { "Name" -> matches.sortedBy { it.name.lowercase() }; "Size" -> matches.sortedByDescending { it.size }; "Type" -> matches.sortedWith(compareBy({ it.mime }, { it.name })); else -> matches.sortedByDescending { it.added } }
    }
    val close: () -> Unit = { model.view(null) }
    if (viewing != null) {
        val item = checkNotNull(viewing)
        MediaViewer(item, filtered.filter { it.category == "Photos" }, model.vault, close)
        return
    }
    BackHandler { when { selected.isNotEmpty() -> selected = emptySet(); folder != null -> folder = null; dialog != null -> dialog = null; else -> model.lock() } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Column { Text("Your private space", style = MaterialTheme.typography.titleLarge); Text("On this device. Only for you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = {
                    IconButton(onClick = { dialog = "settings" }) { Icon(Icons.Outlined.Settings, "Vault settings") }
                    IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock vault") }
                })
        },
        floatingActionButton = { if (selected.isEmpty()) ExtendedFloatingActionButton(onClick = { dialog = "import" }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Import files") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("${state.catalog.items.size} items · ${sizeLabel(state.catalog.items.sumOf { it.size })}", fontWeight = FontWeight.Medium)
                    Text("Original file sizes · encrypted at rest", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your files") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(18.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                items(listOf("All", "Photos", "Videos", "Files", "Folders")) { title -> FilterChip(selected = category == title,
                    onClick = { category = title; folder = null; selected = emptySet() }, label = { Text(title) }) }
            }
            if (state.removalNames.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${state.removalNames.size} originals still outside the vault", fontWeight = FontWeight.Medium)
                    Text("Verified vault copies are ready. Review before removing the originals from shared storage.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { dialog = "remove-originals" }, enabled = !state.busy) { Text("Review originals") }
                        TextButton(onClick = model::keepOriginals, enabled = !state.busy) { Text("Keep originals") }
                    }
                }
            }
            if (state.importProgress != null) Text(state.importProgress.orEmpty(), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            if (state.pendingImports > 0 && !state.busy) Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${state.pendingImports} files are waiting to be imported", fontWeight = FontWeight.Medium)
                    Text("Retry the selected files. Your originals have not been removed.", style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { model.importPending() }, enabled = !state.busy) { Text("Retry import") }; TextButton(onClick = model::discardImports) { Text("Discard selection") } }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (folder != null) IconButton(onClick = { folder = null; selected = emptySet() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "All folders") }
                Text(if (selected.isNotEmpty()) "${selected.size} selected" else folder?.let { id -> state.catalog.folders.find { it.id == id }?.name } ?: if (category == "All") "Everything" else category,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (category == "Folders" && folder == null) IconButton(onClick = { text = ""; dialog = "folder" }) { Icon(Icons.Outlined.CreateNewFolder, "Create folder") }
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sort); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) }
                    DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Newest", "Name", "Type", "Size").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { sort = value; sortMenu = false }) } }
                }
            }
            if (selected.isNotEmpty()) {
                LazyRow(verticalAlignment = Alignment.CenterVertically) {
                    item { TextButton(onClick = { selected = if (selected.size == filtered.size) emptySet() else filtered.map { it.id }.toSet() }) { Text("Select all") } }
                    item { TextButton(onClick = { dialog = "move" }, enabled = !state.busy) { Text("Move") } }
                    if (selected.size == 1) item { TextButton(onClick = { text = state.catalog.items.first { it.id in selected }.name; dialog = "rename" }, enabled = !state.busy) { Text("Rename") } }
                    item { TextButton(onClick = { dialog = "delete" }, enabled = !state.busy) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                    item { TextButton(onClick = { selected = emptySet() }) { Text("Done") } }
                }
            }
            if (category == "Folders" && folder == null) {
                if (state.catalog.folders.isEmpty()) EmptyVault("Make room for a collection", "Create a folder, then select files to move into it.")
                else LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.catalog.folders, key = { it.id }) { f ->
                        Card(onClick = { folder = f.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)); Spacer(Modifier.height(16.dp)); Text(f.name); Text("${state.catalog.items.count { it.folder == f.id }} items", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            } else if (filtered.isEmpty()) EmptyVault(if (query.isNotEmpty()) "Nothing by that name" else "A space that's yours", if (query.isNotEmpty()) "Try another search or category." else "Import photos, videos, or documents.\nYour originals stay exactly where they are.")
            else LazyVerticalGrid(GridCells.Adaptive(155.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(filtered, key = { it.id }) { item ->
                    ItemCard(item, model.vault, item.id in selected,
                        click = { if (selected.isNotEmpty()) selected = if (item.id in selected) selected - item.id else selected + item.id else model.view(item.id) },
                        select = { selected = if (item.id in selected) selected - item.id else selected + item.id })
                }
            }
        }
    }
    when (dialog) {
        "import" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Add to your private space") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text("Copy to vault keeps your originals in Gallery or Files. Move to vault offers a separate removal confirmation after verification."); Spacer(Modifier.height(16.dp));
                FilledTonalButton(onClick = { dialog = null; onPickMedia(folder) }, Modifier.fillMaxWidth()) { Text("Photos & videos") }
                OutlinedButton(onClick = { dialog = null; onPickFiles(folder) }, Modifier.fillMaxWidth()) { Text("Documents & other files") }
                FilledTonalButton(onClick = { dialog = null; onPickMove(folder) }, Modifier.fillMaxWidth()) { Text("Move to vault") }
                Text("Move uses Android Files for write access. Some providers are read-only. Existing verified duplicates can also be moved.", style = MaterialTheme.typography.bodySmall)
                Text("Your auto-lock setting still applies in the picker. After selecting files, enter your PIN again if locked; importing then starts automatically.", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        "remove-originals" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Remove these originals?") },
            text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Text("The vault copies will be reverified before removal. This asks the selected file provider to delete the originals; it may be permanent and may also affect synced cloud files. Gallery trash, backups or other copies may remain.")
                Spacer(Modifier.height(12.dp))
                state.removalNames.forEach { Text("• $it") }
                Spacer(Modifier.height(12.dp))
                Text("Keep a separate secure backup. Uninstalling Calculator or clearing its data destroys the vault. No originals are removed unless you confirm below.")
            } },
            confirmButton = { TextButton(onClick = { dialog = null; model.removeOriginalsConfirmed() }, enabled = !state.busy && state.removalNames.isNotEmpty()) { Text("Remove originals") } },
            dismissButton = { TextButton(onClick = { dialog = null; model.keepOriginals() }) { Text("Keep originals") } })
        "delete" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Permanently delete ${selected.size} items?") }, text = { Text("This removes the selected vault copies and their keys. There is no trash or undo. Originals outside the vault are not affected.") },
            confirmButton = { TextButton(onClick = { model.delete(selected); selected = emptySet(); dialog = null }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        "folder", "rename" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (dialog == "folder") "New folder" else "Rename file") },
            text = { OutlinedTextField(text, { text = it.take(160) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (dialog == "folder") model.folder(text) else model.rename(selected.first(), text); selected = emptySet(); dialog = null }, enabled = com.calcvault.core.ImportTransaction.validName(text)) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        "move" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Move to folder") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { model.move(selected, null); selected = emptySet(); dialog = null }) { Text("No folder") }
                state.catalog.folders.forEach { f -> TextButton(onClick = { model.move(selected, f.id); selected = emptySet(); dialog = null }) { Text(f.name) } }
                if (state.catalog.folders.isEmpty()) Text("Create a folder in the Folders tab first.")
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        "settings" -> SettingsDialog(state, biometricAvailable, onBiometric, model, { dialog = null })
    }
}

@Composable private fun EmptyVault(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun ItemCard(item: VaultItem, repository: VaultRepository, selected: Boolean, click: () -> Unit, select: () -> Unit) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var duration by remember(item.id) { mutableStateOf<Long?>(null) }
    var broken by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        if (item.category != "Files") {
            try {
                val result = withContext(Dispatchers.IO) { previewSlots.withPermit {
                    if (item.category == "Photos") PrivateMedia.VideoPreview(PrivateMedia.image(repository, item, 480), 0)
                    else PrivateMedia.video(repository, item)
                } }
                bitmap = result.bitmap; if (item.category == "Videos") duration = result.durationMs
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { broken = true }
        }
    }
    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = click, onLongClick = select)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(if (item.category == "Files") Icons.Outlined.Description else if (broken) Icons.Outlined.BrokenImage else Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            duration?.let { Text("${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}", Modifier.align(Alignment.BottomEnd).padding(8.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(4.dp), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = select, modifier = Modifier.align(Alignment.TopEnd)) { Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, "Select ${item.name}", tint = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White) }
        }
        Column(Modifier.padding(12.dp)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("${sizeLabel(item.size)} · ${item.mime.substringAfter('/').take(12)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.added)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SettingsDialog(state: AppState, available: Boolean, enableBiometric: () -> Unit, model: VaultViewModel, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Vault settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ACCESS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { close(); model.startPinChange() }, enabled = !state.busy) { Text("Change PIN") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Biometric unlock"); Text(if (available) "Strong device biometrics" else "Not available on this device", style = MaterialTheme.typography.bodySmall) }
                Switch(state.preferences.biometric, onCheckedChange = { if (it) { close(); enableBiometric() } else model.biometricSaved(null, null) }, enabled = !state.busy && (available || state.preferences.biometric))
            }
            HorizontalDivider(); Text("Lock after leaving the app", fontWeight = FontWeight.Medium)
            listOf(0 to "Immediately (recommended)", 15 to "15 seconds", 60 to "1 minute").forEach { (seconds, label) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.preferences(state.preferences.copy(lockSeconds = seconds)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.preferences.lockSeconds == seconds, onClick = { model.preferences(state.preferences.copy(lockSeconds = seconds)) }, enabled = !state.busy); Text(label)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Light theme", Modifier.weight(1f)); Switch(state.preferences.light, { model.preferences(state.preferences.copy(light = it)) }, enabled = !state.busy) }
            HorizontalDivider()
            Text("Your files stay on this device", fontWeight = FontWeight.Medium)
            Text("No account, network access, analytics, or cloud backup. Uninstalling, clearing app data, or losing the encryption key permanently loses vault access. Keep separate originals. A compromised or rooted device can defeat app protections. External originals are not protected by this vault.", style = MaterialTheme.typography.bodySmall)
            Text("Calculator numbers are visible as you type, including a PIN entered to unlock. Percent means divide by 100. To calculate your PIN as a number without unlocking, use parentheses or add +0.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
