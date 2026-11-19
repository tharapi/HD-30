package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.R
import com.example.midi.MidiConnectionUiState
import com.example.ui.MidiControlTarget
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceBright
import com.example.ui.theme.DownbeatCrimson
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.ElectricSkyContainer
import com.example.ui.theme.HardwareBorder
import com.example.ui.theme.LcdBorder
import com.example.ui.theme.LcdMidnight
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.PitchEmerald
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.WarmAmberContainer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MidiConnectionDialog(
    midiState: MidiConnectionUiState,
    sustainPedalDown: Boolean,
    midiMappings: Map<MidiControlTarget, Int>,
    learningTarget: MidiControlTarget?,
    onDismiss: () -> Unit,
    onRefreshUsb: () -> Unit,
    onConnectUsbDevice: (Int) -> Unit,
    onDisconnectUsbDevice: (Int) -> Unit,
    onStartBleScan: () -> Unit,
    onStopBleScan: () -> Unit,
    onConnectBleDevice: (String) -> Unit,
    onStartMidiLearn: (MidiControlTarget) -> Unit,
    onCancelMidiLearn: () -> Unit,
    onResetDefaultMappings: () -> Unit
) {
    val context = LocalContext.current
    var permissionDeniedNote by remember { mutableStateOf(false) }

    val requiredBlePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            permissionDeniedNote = false
            onStartBleScan()
        } else {
            permissionDeniedNote = true
        }
    }

    fun launchBleScanWithPermissions() {
        val hasAll = requiredBlePermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (hasAll) {
            permissionDeniedNote = false
            onStartBleScan()
        } else {
            blePermissionLauncher.launch(requiredBlePermissions)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = AnodizedSlate,
            border = BorderStroke(1.5.dp, HardwareBorder),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Piano,
                            contentDescription = null,
                            tint = ElectricSky
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.midi_setup_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "USB-C OTG & Bluetooth 5.0 BLE MIDI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlateText
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("midi_dialog_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close MIDI dialog",
                            tint = MutedSlateText
                        )
                    }
                }

                // Scrollable body
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Live Telemetry Readout
                    Surface(
                        color = LcdMidnight,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, LcdBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (midiState.connectedDeviceName != null) {
                                        "ACTIVE: ${midiState.connectedDeviceName}"
                                    } else {
                                        "NO HARDWARE KEYBOARD LINKED"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (midiState.connectedDeviceName != null) PitchEmerald else WarmAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    color = if (sustainPedalDown) PitchEmerald.copy(alpha = 0.2f) else ConsoleSurface,
                                    shape = MaterialTheme.shapes.extraSmall,
                                    border = BorderStroke(
                                        1.dp,
                                        if (sustainPedalDown) PitchEmerald else HardwareBorder
                                    )
                                ) {
                                    Text(
                                        text = if (sustainPedalDown) "PEDAL DOWN" else "PEDAL UP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (sustainPedalDown) PitchEmerald else MutedSlateText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Last Event: ${midiState.lastEventText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricSky
                            )

                            midiState.statusMessage?.let { status ->
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!midiState.isMidiSupported) {
                        Surface(
                            color = DownbeatCrimson.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, DownbeatCrimson),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Android MIDI Service is unavailable on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = DownbeatCrimson,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Section 1: USB-C / Wired MIDI Devices
                    Surface(
                        color = ConsoleSurface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, HardwareBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Usb,
                                        contentDescription = null,
                                        tint = ElectricSky,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "USB & CONNECTED MIDI DEVICES",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                OutlinedButton(
                                    onClick = onRefreshUsb,
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("midi_refresh_usb_button"),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    border = BorderStroke(1.dp, HardwareBorder)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.midi_refresh_usb),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            if (midiState.availableDevices.isEmpty()) {
                                Text(
                                    text = "No USB MIDI keyboards detected. Plug your M-VAVE SMK-37 PRO into the Android device using a USB-C / OTG cable, or scan via Bluetooth below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlateText
                                )
                            } else {
                                midiState.availableDevices.forEach { dev ->
                                    Surface(
                                        color = ConsoleSurfaceBright,
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(
                                            1.dp,
                                            if (dev.isConnected) PitchEmerald else HardwareBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = if (dev.isBluetooth) Icons.Filled.Bluetooth else Icons.Filled.Usb,
                                                    contentDescription = null,
                                                    tint = if (dev.isConnected) PitchEmerald else ElectricSky,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = dev.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (dev.manufacturer.isNotBlank()) {
                                                        Text(
                                                            text = dev.manufacturer,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MutedSlateText
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    if (dev.isConnected) onDisconnectUsbDevice(dev.id)
                                                    else onConnectUsbDevice(dev.id)
                                                },
                                                modifier = Modifier
                                                    .height(48.dp)
                                                    .testTag("midi_device_btn_${dev.id}"),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (dev.isConnected) {
                                                        DownbeatCrimson.copy(alpha = 0.2f)
                                                    } else {
                                                        ElectricSky
                                                    },
                                                    contentColor = if (dev.isConnected) DownbeatCrimson else StudioCharcoal
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp)
                                            ) {
                                                Text(
                                                    text = if (dev.isConnected) "Disconnect" else "Connect",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Bluetooth BLE MIDI Scanner
                    Surface(
                        color = ConsoleSurface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, HardwareBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                                        contentDescription = null,
                                        tint = ElectricSky,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "BLUETOOTH BLE MIDI",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (midiState.isScanningBle) onStopBleScan()
                                        else launchBleScanWithPermissions()
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("midi_scan_ble_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (midiState.isScanningBle) WarmAmber else ElectricSky,
                                        contentColor = StudioCharcoal
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp)
                                ) {
                                    if (midiState.isScanningBle) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = StudioCharcoal
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.midi_stop_scan),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.midi_scan_ble),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }

                            if (permissionDeniedNote) {
                                Text(
                                    text = "Bluetooth permission was denied. Enable Nearby Devices / Bluetooth permission in Android Settings to pair wirelessly.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WarmAmber
                                )
                            }

                            if (midiState.discoveredBleDevices.isEmpty()) {
                                Text(
                                    text = "Turn on BT on your M-VAVE SMK-37 PRO and tap \"Scan BLE MIDI\" to connect wirelessly without cables.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedSlateText
                                )
                            } else {
                                midiState.discoveredBleDevices.forEach { ble ->
                                    Surface(
                                        color = ConsoleSurfaceBright,
                                        shape = MaterialTheme.shapes.small,
                                        border = BorderStroke(
                                            1.dp,
                                            if (ble.isMidiService) ElectricSky.copy(alpha = 0.6f) else HardwareBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = ble.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (ble.isMidiService) {
                                                        Surface(
                                                            color = ElectricSkyContainer,
                                                            shape = MaterialTheme.shapes.extraSmall
                                                        ) {
                                                            Text(
                                                                text = "BLE MIDI",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = ElectricSky,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "${ble.address} · ${ble.rssi} dBm",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MutedSlateText
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { onConnectBleDevice(ble.address) },
                                                modifier = Modifier
                                                    .height(48.dp)
                                                    .testTag("midi_ble_connect_${ble.address}"),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = ElectricSkyContainer,
                                                    contentColor = ElectricSky
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp)
                                            ) {
                                                Text(
                                                    text = "Pair",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = HardwareBorder)

                    // Section 3: SMK-37 PRO Fader, Knob & Pad MIDI Learn Mapping
                    Surface(
                        color = ConsoleSurface,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, HardwareBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Sensors,
                                        contentDescription = null,
                                        tint = WarmAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "SMK-37 PRO KNOB / FADER / PAD LEARN",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Tap any control below, then move a fader/knob/pad on your keyboard. CC #64 acts as Sustain Pedal when unassigned.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MutedSlateText
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = onResetDefaultMappings,
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("midi_reset_mappings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.RestartAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MutedSlateText
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Reset",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MutedSlateText
                                    )
                                }
                            }

                            if (learningTarget != null) {
                                Surface(
                                    color = WarmAmberContainer,
                                    shape = MaterialTheme.shapes.small,
                                    border = BorderStroke(1.dp, WarmAmber),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Listening... Move a knob, fader, or pad to map \"${learningTarget.label}\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = WarmAmber,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = onCancelMidiLearn,
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("midi_cancel_learn_button")
                                        ) {
                                            Text("Cancel", color = WarmAmber)
                                        }
                                    }
                                }
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MidiControlTarget.entries.forEach { target ->
                                    val isLearning = learningTarget == target
                                    val mappedCc = midiMappings[target] ?: target.defaultCc
                                    val ccText = if (mappedCc >= 0) "CC #$mappedCc" else "Unassigned"
                                    FilterChip(
                                        selected = isLearning,
                                        onClick = {
                                            if (isLearning) onCancelMidiLearn()
                                            else onStartMidiLearn(target)
                                        },
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("midi_learn_${target.name.lowercase()}"),
                                        label = {
                                            Text(
                                                text = if (isLearning) {
                                                    "${target.label}: LEARN..."
                                                } else {
                                                    "${target.label} · $ccText"
                                                },
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = ConsoleSurfaceBright,
                                            labelColor = MaterialTheme.colorScheme.onSurface,
                                            selectedContainerColor = WarmAmberContainer,
                                            selectedLabelColor = WarmAmber
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isLearning,
                                            borderColor = HardwareBorder,
                                            selectedBorderColor = WarmAmber
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
