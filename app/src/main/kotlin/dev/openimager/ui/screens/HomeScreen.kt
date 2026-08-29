package dev.openimager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openimager.core.image.ImageWriter
import dev.openimager.core.image.WritePhase
import dev.openimager.storage.StorageTarget
import dev.openimager.ui.CatalogueState
import dev.openimager.ui.ImagerUiState
import dev.openimager.write.WriteState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ImagerUiState,
    onChooseHardware: () -> Unit,
    onChooseOs: () -> Unit,
    onChooseStorage: () -> Unit,
    onCustomise: () -> Unit,
    onWrite: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    onDismissMessage: () -> Unit,
    onRetryCatalogue: () -> Unit,
    onVerifyChanged: (Boolean) -> Unit,
    onCustomImagePicked: (Uri) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RPi Open Imager") },
                actions = {
                    if (state.catalogue is CatalogueState.Error) {
                        TextButton(onClick = onRetryCatalogue) { Text("Retry") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val write = state.write) {
                is WriteState.Running -> WritePanel(write, onCancel)
                is WriteState.Finished -> ResultPanel(
                    title = "${write.imageLabel} written",
                    body = buildString {
                        append("${write.targetLabel} is ready.")
                        if (write.result.verified) append(" The card was read back and matches.")
                        write.result.customization?.let { applied ->
                            if (applied.filesWritten.isNotEmpty()) {
                                append(" First boot settings applied (${applied.filesWritten.joinToString(", ")}).")
                            }
                        }
                    },
                    icon = Icons.Filled.CheckCircle,
                    onDismiss = onDismissResult,
                )
                is WriteState.Failed -> ResultPanel(
                    title = "Write failed",
                    body = write.message,
                    icon = Icons.Filled.ErrorOutline,
                    onDismiss = onDismissResult,
                )
                WriteState.Cancelled -> ResultPanel(
                    title = "Write cancelled",
                    body = "The card was left partly written, so write it again before using it.",
                    icon = Icons.Filled.ErrorOutline,
                    onDismiss = onDismissResult,
                )
                WriteState.Idle -> Unit
            }

            SelectorCard(
                icon = Icons.Filled.DeveloperBoard,
                label = "Raspberry Pi Device",
                value = state.selectedHardware?.name ?: "No filtering",
                supporting = state.selectedHardware?.description ?: "Show images for every board",
                enabled = state.write !is WriteState.Running,
                onClick = onChooseHardware,
            )

            SelectorCard(
                icon = Icons.Outlined.Memory,
                label = "Operating System",
                value = state.selection?.label ?: "Choose OS",
                supporting = when {
                    state.catalogue is CatalogueState.Error && state.selection == null ->
                        (state.catalogue as CatalogueState.Error).message
                    else -> state.selection?.subtitle.orEmpty()
                },
                enabled = state.write !is WriteState.Running,
                onClick = onChooseOs,
            )

            SelectorCard(
                icon = Icons.Filled.SdStorage,
                label = "Storage",
                value = state.selectedStorage?.label ?: "Choose storage",
                supporting = state.selectedStorage?.subtitle
                    ?: "Connect a card reader or USB drive",
                enabled = state.write !is WriteState.Running,
                onClick = onChooseStorage,
            )

            if (state.customizationSupported) {
                Card(onClick = onCustomise, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("OS customisation", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.customization.enabled) {
                                    "On - hostname, user, Wi-Fi and SSH are applied on first boot"
                                } else {
                                    "Off - the image boots with its own defaults"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.verifyAfterWrite,
                    onCheckedChange = onVerifyChanged,
                    enabled = state.write !is WriteState.Running,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Verify after writing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Reads the card back and compares it with the image",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = { confirming = true },
                enabled = state.canWrite,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Write")
            }

            TextButton(onClick = { pickImage.launch(arrayOf("*/*")) }) {
                Text("Use a custom image from this device")
            }
        }
    }

    if (confirming) {
        val target = state.selectedStorage
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Erase ${target?.label.orEmpty()}?") },
            text = {
                Text(
                    "Everything on ${target?.label ?: "the card"} " +
                        "(${StorageTarget.formatCapacity(target?.sizeBytes ?: 0)}) will be erased " +
                        "and replaced with ${state.selection?.label}.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    onWrite()
                }) { Text("Erase and write") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun SelectorCard(
    icon: ImageVector,
    label: String,
    value: String,
    supporting: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
                if (supporting.isNotEmpty()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WritePanel(write: WriteState.Running, onCancel: () -> Unit) {
    val progress = write.progress
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (progress.phase) {
                    WritePhase.PREPARING -> "Preparing"
                    WritePhase.WRITING -> "Writing ${write.imageLabel}"
                    WritePhase.VERIFYING -> "Verifying ${write.targetLabel}"
                    WritePhase.CUSTOMISING -> "Applying first boot settings"
                    WritePhase.FINISHED -> "Finishing"
                },
                style = MaterialTheme.typography.titleMedium,
            )

            // The catalogue does not always publish the extracted size; fall back to download bytes.
            val fraction = progress.fraction
                ?: (progress.downloadedBytes.toFloat() / write.sourceBytes).takeIf { write.sourceBytes > 0 }

            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                buildString {
                    append(ImageWriter.formatSize(progress.bytesProcessed))
                    if (progress.totalBytes > 0) append(" of ${ImageWriter.formatSize(progress.totalBytes)}")
                    if (progress.bytesPerSecond > 0) {
                        append(" - ${ImageWriter.formatSize(progress.bytesPerSecond)}/s")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Keep the card connected. You can leave the app; writing continues in the background.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ResultPanel(title: String, body: String, icon: ImageVector, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
internal fun LoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
