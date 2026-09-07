/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.music.vivi.ui.screens.settings.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.LocalViviConnectManager
import com.music.vivi.R
import com.music.vivi.connect.ClientConnectionState
import com.music.vivi.connect.ConnectDevice
import com.music.vivi.constants.AccountEmailKey
import com.music.vivi.constants.AccountNameKey
import com.music.vivi.constants.ViviConnectDeviceNameKey
import com.music.vivi.constants.ViviConnectEnabledKey
import com.music.vivi.constants.ViviConnectSameAccountOnlyKey
import com.music.vivi.ui.component.ExpressiveSettingGroup
import com.music.vivi.ui.component.IconButton
import com.music.vivi.ui.component.Material3SettingsItem
import com.music.vivi.ui.utils.backToMain
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.rememberPreference
import kotlinx.coroutines.launch

@Composable
fun ViviConnectSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val connectManager = LocalViviConnectManager.current

    val (connectEnabled, onConnectEnabledChange) = rememberPreference(ViviConnectEnabledKey, true)
    val (deviceName, onDeviceNameChange) = rememberPreference(
        ViviConnectDeviceNameKey,
        connectManager?.deviceName?.collectAsState()?.value ?: "My Device"
    )
    val (sameAccountOnly, onSameAccountOnlyChange) = rememberPreference(ViviConnectSameAccountOnlyKey, true)

    val accountEmail by rememberPreference(AccountEmailKey, "")
    val accountName by rememberPreference(AccountNameKey, "")
    val hasAccount = accountEmail.isNotEmpty() || accountName.isNotEmpty()

    var showEditNameDialog by remember { mutableStateOf(false) }
    var tempDeviceName by remember { mutableStateOf(deviceName) }

    val discoveredDevices by connectManager?.discoveredDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val connectionState by connectManager?.connectionState?.collectAsState() ?: remember { mutableStateOf(ClientConnectionState.DISCONNECTED) }
    val connectedDevice by connectManager?.connectedDevice?.collectAsState() ?: remember { mutableStateOf(null) }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.home_speaker_devices),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Vivi Connect",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Control playback and seamlessly transfer music across devices on your local Wi-Fi, just like Spotify Connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Main Controls Group
        ExpressiveSettingGroup(
            title = "Configuration",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.home_speaker_devices),
                    title = { Text("Enable Vivi Connect") },
                    description = { Text("Discover and be discovered by other devices on this network") },
                    trailingContent = {
                        Switch(
                            checked = connectEnabled,
                            onCheckedChange = onConnectEnabledChange
                        )
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.edit),
                    title = { Text("Device Name") },
                    description = { Text(deviceName) },
                    enabled = connectEnabled,
                    onClick = {
                        tempDeviceName = deviceName
                        showEditNameDialog = true
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.google),
                    title = { Text("Same Account Only") },
                    description = {
                        Text(
                            if (hasAccount) {
                                "Only pair with devices signed in as ${accountEmail.ifEmpty { accountName }}"
                            } else {
                                "Sign in to your account to restrict device discovery, or disable to allow all local devices"
                            }
                        )
                    },
                    enabled = connectEnabled,
                    trailingContent = {
                        Switch(
                            checked = sameAccountOnly,
                            onCheckedChange = onSameAccountOnlyChange,
                            enabled = connectEnabled
                        )
                    }
                )
            )
        )

        // Nearby Devices Section
        if (connectEnabled) {
            Text(
                text = "Devices on Network",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )

            // Current Device Row
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phone_android),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "This Device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = if (connectedDevice != null) "Controlling ${connectedDevice?.name}" else "Active player",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Discovered Remote Devices
            if (discoveredDevices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.home_speaker_devices),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Searching for nearby devices on Wi-Fi...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Make sure both devices are on the same Wi-Fi network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (device in discoveredDevices) {
                        val isConnected = connectedDevice?.id == device.id && connectionState == ClientConnectionState.CONNECTED
                        val isConnecting = connectedDevice?.id == device.id && connectionState == ClientConnectionState.CONNECTING

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isConnected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (isConnected) {
                                        connectManager?.disconnectFromDevice()
                                    } else {
                                        connectManager?.connectToDevice(device)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.home_speaker_devices),
                                    contentDescription = null,
                                    tint = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when {
                                            isConnected -> "Connected • Tap to disconnect"
                                            isConnecting -> "Connecting..."
                                            else -> "Ready to connect • Tap to control"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isConnected) {
                                    TextButton(
                                        onClick = { connectManager?.transferPlaybackToThisDevice() }
                                    ) {
                                        Text("Play Here")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Edit Device Name Dialog
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Edit Device Name") },
            text = {
                OutlinedTextField(
                    value = tempDeviceName,
                    onValueChange = { tempDeviceName = it },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempDeviceName.isNotBlank()) {
                            onDeviceNameChange(tempDeviceName.trim())
                        }
                        showEditNameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    TopAppBar(
        title = { Text("Vivi Connect") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
