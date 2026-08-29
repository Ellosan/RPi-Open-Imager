package dev.openimager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.openimager.core.customization.CustomizationSettings
import dev.openimager.core.customization.Sha512Crypt
import dev.openimager.core.customization.WpaPsk

/**
 * The equivalent of Raspberry Pi Imager's "OS customisation" sheet. Passwords are turned into a
 * crypt hash and a WPA PSK the moment they are saved, so nothing here keeps them in the clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationScreen(
    settings: CustomizationSettings,
    onSave: (CustomizationSettings) -> Unit,
    onClose: () -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var hostname by rememberSaveable { mutableStateOf(settings.hostname) }
    var username by rememberSaveable { mutableStateOf(settings.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var wifiSsid by rememberSaveable { mutableStateOf(settings.wifiSsid) }
    var wifiPassword by rememberSaveable { mutableStateOf("") }
    var wifiHidden by rememberSaveable { mutableStateOf(settings.wifiHidden) }
    var wifiCountry by rememberSaveable { mutableStateOf(settings.wifiCountry) }
    var timezone by rememberSaveable { mutableStateOf(settings.timezone) }
    var keyboard by rememberSaveable { mutableStateOf(settings.keyboardLayout) }
    var enableSsh by rememberSaveable { mutableStateOf(settings.enableSsh) }
    var sshKeys by rememberSaveable { mutableStateOf(settings.sshAuthorizedKeys) }

    // The PSK is derived from the network name, so a new name needs the password entering again.
    val ssidChanged = wifiSsid.isNotBlank() && wifiSsid != settings.wifiSsid
    val needsWifiPassword = ssidChanged && wifiPassword.isBlank()
    val needsPassword = username.isNotBlank() && password.isBlank() && settings.passwordCrypt.isBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OS customisation") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToggleRow(
                title = "Apply customisation",
                subtitle = "Writes first boot settings to the card after the image",
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
            HorizontalDivider()

            SectionTitle("General")
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it.trim() },
                label = { Text("Hostname") },
                supportingText = { Text("Reachable as <hostname>.local on the network") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text("Username") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (settings.passwordCrypt.isBlank()) "Password" else "New password") },
                supportingText = {
                    Text(
                        when {
                            needsPassword -> "A password is required for the account"
                            settings.passwordCrypt.isNotBlank() && password.isBlank() ->
                                "Saved as a hash - leave empty to keep it"
                            else -> "Stored as a SHA-512 crypt hash, never in the clear"
                        },
                    )
                },
                isError = needsPassword,
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Wireless LAN")
            OutlinedTextField(
                value = wifiSsid,
                onValueChange = { wifiSsid = it },
                label = { Text("Network name (SSID)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wifiPassword,
                onValueChange = { wifiPassword = it },
                label = { Text("Network password") },
                supportingText = {
                    Text(
                        when {
                            needsWifiPassword -> "Enter the password again after changing the network name"
                            settings.wifiPsk.isNotBlank() && wifiPassword.isBlank() ->
                                "Saved as a PSK - leave empty to keep it"
                            else -> "Converted to a WPA PSK before it reaches the card"
                        },
                    )
                },
                isError = needsWifiPassword,
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wifiCountry,
                onValueChange = { wifiCountry = it.uppercase().take(2) },
                label = { Text("Wireless LAN country") },
                supportingText = { Text("Two letter code, for example GB, DE or US") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = "Hidden network",
                subtitle = "Scan for the SSID instead of waiting for a beacon",
                checked = wifiHidden,
                onCheckedChange = { wifiHidden = it },
                enabled = enabled,
            )

            SectionTitle("Locale")
            OutlinedTextField(
                value = timezone,
                onValueChange = { timezone = it.trim() },
                label = { Text("Time zone") },
                supportingText = { Text("For example Europe/London") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keyboard,
                onValueChange = { keyboard = it.trim() },
                label = { Text("Keyboard layout") },
                supportingText = { Text("For example gb, de or us") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Remote access")
            ToggleRow(
                title = "Enable SSH",
                subtitle = "Starts the SSH server on first boot",
                checked = enableSsh,
                onCheckedChange = { enableSsh = it },
                enabled = enabled,
            )
            OutlinedTextField(
                value = sshKeys,
                onValueChange = { sshKeys = it },
                label = { Text("Authorised public keys") },
                supportingText = { Text("One key per line. With keys present, password login is turned off.") },
                enabled = enabled && enableSsh,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            enabled = enabled,
                            hostname = hostname,
                            username = username,
                            passwordCrypt = when {
                                password.isNotBlank() -> Sha512Crypt.hash(password)
                                username.isBlank() -> ""
                                else -> settings.passwordCrypt
                            },
                            wifiSsid = wifiSsid,
                            wifiPsk = when {
                                wifiPassword.isNotBlank() -> WpaPsk.derive(wifiSsid, wifiPassword)
                                wifiSsid.isBlank() -> ""
                                else -> settings.wifiPsk
                            },
                            wifiHidden = wifiHidden,
                            wifiCountry = wifiCountry,
                            timezone = timezone,
                            keyboardLayout = keyboard,
                            enableSsh = enableSsh,
                            sshAuthorizedKeys = if (enableSsh) sshKeys else "",
                            sshPasswordAuthentication = sshKeys.isBlank(),
                        ),
                    )
                },
                enabled = !needsWifiPassword && !needsPassword,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
