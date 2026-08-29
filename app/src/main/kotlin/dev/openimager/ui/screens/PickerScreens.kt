package dev.openimager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.openimager.core.oslist.HardwareDevice
import dev.openimager.core.oslist.OsListItem
import dev.openimager.storage.StorageTarget
import dev.openimager.ui.ImagerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
fun HardwarePickerScreen(
    hardware: List<HardwareDevice>,
    selected: HardwareDevice?,
    onSelect: (HardwareDevice?) -> Unit,
    onClose: () -> Unit,
) {
    PickerScaffold(title = "Raspberry Pi Device", onBack = onClose) { modifier ->
        LazyColumn(modifier) {
            item {
                PickerRow(
                    title = "No filtering",
                    subtitle = "Show every image in the catalogue",
                    trailingSelected = selected == null,
                    onClick = { onSelect(null) },
                    leading = { Icon(Icons.Filled.DeveloperBoard, contentDescription = null) },
                )
                HorizontalDivider()
            }
            items(hardware, key = { it.name }) { device ->
                PickerRow(
                    title = device.name,
                    subtitle = device.description.orEmpty(),
                    trailingSelected = device.name == selected?.name,
                    onClick = { onSelect(device) },
                    leading = { RemoteIcon(device.icon) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun OsPickerScreen(
    state: ImagerUiState,
    onOpenCategory: (OsListItem) -> Unit,
    onSelect: (OsListItem) -> Unit,
    onCustomImagePicked: (Uri) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val level = state.browseStack.lastOrNull()
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onCustomImagePicked(it)
        }
    }

    PickerScaffold(
        title = level?.title ?: "Operating System",
        onBack = onBack,
        actions = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        },
    ) { modifier ->
        LazyColumn(modifier) {
            if (state.browseStack.size <= 1) {
                item {
                    PickerRow(
                        title = "Use custom",
                        subtitle = "Select a .img, .img.xz, .img.gz or .zip file from this device",
                        onClick = { pickImage.launch(arrayOf("*/*")) },
                        leading = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                    )
                    HorizontalDivider()
                }
            }

            if (level?.loading == true) {
                item { LoadingRow("Loading list") }
            }
            level?.error?.let { error ->
                item { PickerRow(title = "Could not load this list", subtitle = error, onClick = onBack) }
            }

            items(level?.items.orEmpty(), key = { it.name + it.url.orEmpty() }) { item ->
                PickerRow(
                    title = item.name,
                    subtitle = buildString {
                        append(item.description.orEmpty())
                        if (item.releaseDate != null) {
                            if (isNotEmpty()) append(" - ")
                            append("Released ${item.releaseDate}")
                        }
                        if (item.extractSize > 0) {
                            if (isNotEmpty()) append(" - ")
                            append(dev.openimager.core.image.ImageWriter.formatSize(item.extractSize))
                        }
                    },
                    onClick = { if (item.isCategory) onOpenCategory(item) else onSelect(item) },
                    leading = { RemoteIcon(item.icon) },
                    trailing = {
                        if (item.isCategory) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun StoragePickerScreen(
    state: ImagerUiState,
    onSelect: (StorageTarget) -> Unit,
    onRefresh: () -> Unit,
    onRootAccessChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    PickerScaffold(
        title = "Storage",
        onBack = onClose,
        actions = {
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        },
    ) { modifier ->
        LazyColumn(modifier) {
            if (state.storageLoading) item { LoadingRow("Looking for card readers") }

            if (state.storage.isEmpty() && !state.storageLoading) {
                item {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No storage found", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "There are two ways to write an SD card from a phone:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "1. Plug a card reader or USB drive into a USB OTG adapter. Android " +
                                "asks for access the first time each reader is used.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "2. Use the card slot built into this phone, which needs root because " +
                                "Android does not let apps reach the raw card any other way.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            items(state.storage, key = { it.id }) { target ->
                PickerRow(
                    title = target.label,
                    subtitle = target.subtitle,
                    trailingSelected = target.id == state.selectedStorage?.id,
                    onClick = { onSelect(target) },
                    leading = {
                        Icon(
                            when (target) {
                                is StorageTarget.Usb -> Icons.Filled.Usb
                                is StorageTarget.Root -> Icons.Filled.SdStorage
                            },
                            contentDescription = null,
                        )
                    },
                )
                HorizontalDivider()
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Use this phone's SD card slot", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Needs root. Lists removable /dev/block devices, unmounts the card and " +
                                "writes it through su. Everything on the card is erased.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = state.rootAccessEnabled, onCheckedChange = onRootAccessChanged)
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    trailingSelected: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingSelected) Icon(Icons.Filled.Check, contentDescription = "Selected") else trailing()
    }
}

@Composable
private fun RemoteIcon(url: String?) {
    if (url.isNullOrBlank()) {
        Icon(Icons.Filled.DeveloperBoard, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(40.dp))
    }
}
