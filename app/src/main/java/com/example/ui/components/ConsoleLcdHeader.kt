package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.MetronomeBeatSnapshot
import com.example.model.PitchTheory
import com.example.model.TemperamentMode
import com.example.ui.HarmonyUiState
import com.example.ui.MetronomeUiState
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.DownbeatCrimson
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.LcdBorder
import com.example.ui.theme.LcdMidnight
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.PitchEmerald
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.WarmAmber
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun ConsoleLcdHeader(
    harmonyState: HarmonyUiState,
    metronomeState: MetronomeUiState,
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    onStopAllSound: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasActiveSound = harmonyState.activeWrittenMidis.isNotEmpty() || metronomeState.isPlaying

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LcdMidnight,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.5.dp, LcdBorder),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Telemetry Hardware Strip: Title, Beat LEDs, Panic Stop, Sign Out
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = ElectricSky.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.extraSmall,
                        border = BorderStroke(1.dp, ElectricSky.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "HD-300",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricSky,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    BeatLedStrip(beatSnapshotFlow = beatSnapshotFlow)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onStopAllSound,
                        enabled = hasActiveSound,
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("panic_stop_button"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = DownbeatCrimson.copy(alpha = 0.22f),
                            contentColor = DownbeatCrimson,
                            disabledContainerColor = ConsoleSurface.copy(alpha = 0.5f),
                            disabledContentColor = MutedSlateText
                        ),
                        border = if (hasActiveSound) BorderStroke(1.dp, DownbeatCrimson) else null
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeOff,
                            contentDescription = stringResource(R.string.cd_stop_all),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "MUTE",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.sign_out),
                            tint = MutedSlateText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Main Backlit LCD Matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tempo Cell
                LcdCell(
                    label = "TEMPO",
                    value = "${metronomeState.bpm}",
                    sub = "${metronomeState.timeSignature.label} · ${metronomeState.metronomeSound.label.uppercase()}",
                    accentColor = if (metronomeState.isPlaying) PitchEmerald else ElectricSky,
                    modifier = Modifier.weight(1f)
                )

                // Calibration & Temperament Cell
                val rootLabel = PitchTheory.pitchClassName(harmonyState.effectiveRootIndex)
                val tempShort = if (harmonyState.temperament == TemperamentMode.PURE) "PURE ($rootLabel)" else "EQUAL"
                LcdCell(
                    label = "A4 PITCH",
                    value = String.format(Locale.US, "%.1f", harmonyState.referencePitchHz),
                    sub = "$tempShort · KEY ${harmonyState.transposition.shortLabel}",
                    accentColor = WarmAmber,
                    modifier = Modifier.weight(1.25f)
                )

                // Active Harmony / Chord Cell
                LcdCell(
                    label = "HARMONY",
                    value = harmonyState.chordLabel,
                    sub = harmonyState.voice.label.uppercase(),
                    accentColor = ElectricSky,
                    modifier = Modifier.weight(1.1f)
                )
            }

            // Active Notes & Pure Cent Deviation Readout Strip
            if (harmonyState.activePitchInfos.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    harmonyState.activePitchInfos.forEach { info ->
                        val centsText = if (harmonyState.temperament == TemperamentMode.PURE) {
                            val sign = if (info.centsOffsetFromEt >= 0) "+" else ""
                            String.format(Locale.US, " %s%.1f¢ (%s)", sign, info.centsOffsetFromEt, info.intervalName)
                        } else {
                            String.format(Locale.US, " %.1f Hz", info.frequencyHz)
                        }
                        val label = if (info.writtenName == info.soundingName) {
                            "${info.soundingName}$centsText"
                        } else {
                            "${info.writtenName}→${info.soundingName}$centsText"
                        }
                        Surface(
                            color = ConsoleSurface,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, ElectricSky.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricSky,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeatLedStrip(
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>
) {
    val beatSnap by beatSnapshotFlow.collectAsStateWithLifecycle()
    val ledDesc = stringResource(R.string.cd_beat_led)

    // Beat LED Strip (caps visually at 12 LEDs cleanly)
    Row(
        modifier = Modifier.semantics { contentDescription = ledDesc },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val total = beatSnap.totalBeats.coerceIn(2, 12)
        for (b in 0 until total) {
            val isCurrent = beatSnap.isPlaying && beatSnap.beatIndex == b
            val targetColor = when {
                !isCurrent -> ConsoleSurface
                beatSnap.isMutedBar -> WarmAmber.copy(alpha = 0.7f)
                b == 0 -> DownbeatCrimson
                else -> PitchEmerald
            }
            val ledColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(durationMillis = 70),
                label = "beat_led_$b"
            )
            Box(
                modifier = Modifier
                    .size(if (b == 0) 11.dp else 8.dp)
                    .clip(CircleShape)
                    .background(ledColor)
            )
        }
    }
}

@Composable
private fun LcdCell(
    label: String,
    value: String,
    sub: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = StudioCharcoal.copy(alpha = 0.65f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, LcdBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MutedSlateText,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
