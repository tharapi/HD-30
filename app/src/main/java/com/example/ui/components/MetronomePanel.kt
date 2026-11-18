package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MetronomeSound
import com.example.model.SubdivisionMix
import com.example.model.TimeSignature
import com.example.ui.MetronomeUiState
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
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MetronomePanel(
    state: MetronomeUiState,
    onTogglePlay: () -> Unit,
    onBpmChanged: (Int) -> Unit,
    onAdjustBpm: (Int) -> Unit,
    onTapTempo: () -> Unit,
    onTimeSignatureSelected: (TimeSignature) -> Unit,
    onMetronomeSoundSelected: (MetronomeSound) -> Unit,
    onSubdivisionMixChanged: ((SubdivisionMix) -> SubdivisionMix) -> Unit,
    onToggleMuteBar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Tempo & Transport Deck
        ConsoleSectionCard(title = "TEMPO & CONDUCTOR TRANSPORT") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Conductor Pendulum Visual
                    ConductorPendulumGauge(
                        isPlaying = state.isPlaying,
                        beatIndex = state.beatSnapshot.beatIndex,
                        isMutedBar = state.beatSnapshot.isMutedBar,
                        modifier = Modifier.size(92.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${state.bpm}",
                                style = MaterialTheme.typography.displaySmall,
                                color = if (state.isPlaying) PitchEmerald else ElectricSky
                            )
                            Text(
                                text = "BPM",
                                style = MaterialTheme.typography.titleSmall,
                                color = MutedSlateText,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        Text(
                            text = tempoItalianMarking(state.bpm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarmAmber
                        )
                        if (state.isPlaying && state.beatSnapshot.isMutedBar) {
                            Text(
                                text = "SILENT DRILL BAR",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarmAmber
                            )
                        }
                    }

                    // Primary Start/Stop + Tap Tempo column
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Button(
                            onClick = onTogglePlay,
                            modifier = Modifier
                                .width(116.dp)
                                .height(48.dp)
                                .testTag("metronome_play_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isPlaying) DownbeatCrimson else PitchEmerald,
                                contentColor = StudioCharcoal
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.cd_play_metronome),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.isPlaying) "STOP" else "START",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        OutlinedButton(
                            onClick = onTapTempo,
                            modifier = Modifier
                                .width(116.dp)
                                .height(48.dp)
                                .testTag("tap_tempo_button"),
                            border = BorderStroke(1.dp, WarmAmber.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = WarmAmberContainer.copy(alpha = 0.45f),
                                contentColor = WarmAmber
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(
                                imageVector = Icons.Filled.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.tap_tempo),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                // BPM Slider & Fine Steppers
                Slider(
                    value = state.bpm.toFloat(),
                    onValueChange = { onBpmChanged(it.toInt()) },
                    valueRange = 32f..280f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bpm_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricSky,
                        activeTrackColor = ElectricSky,
                        inactiveTrackColor = ConsoleSurfaceBright
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallStepButton(
                        label = "-5",
                        onClick = { onAdjustBpm(-5) },
                        modifier = Modifier.weight(1f),
                        testTag = "bpm_minus_5"
                    )
                    SmallStepButton(
                        label = "-1",
                        onClick = { onAdjustBpm(-1) },
                        modifier = Modifier.weight(1f),
                        testTag = "bpm_minus_1"
                    )
                    SmallStepButton(
                        label = "+1",
                        onClick = { onAdjustBpm(1) },
                        modifier = Modifier.weight(1f),
                        testTag = "bpm_plus_1"
                    )
                    SmallStepButton(
                        label = "+5",
                        onClick = { onAdjustBpm(5) },
                        modifier = Modifier.weight(1f),
                        testTag = "bpm_plus_5"
                    )
                }
            }
        }

        // 2. Time Signature, Timbre & Mute Drill
        ConsoleSectionCard(title = "METER, CLICK TIMBRE & PULSE DRILL") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Time Signature",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlateText
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimeSignature.entries.forEach { ts ->
                        val selected = state.timeSignature == ts
                        FilterChip(
                            selected = selected,
                            onClick = { onTimeSignatureSelected(ts) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("meter_${ts.name.lowercase()}"),
                            label = {
                                Text(
                                    text = ts.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ConsoleSurface,
                                selectedContainerColor = ElectricSkyContainer,
                                selectedLabelColor = ElectricSky
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = HardwareBorder,
                                selectedBorderColor = ElectricSky
                            )
                        )
                    }
                }

                Text(
                    text = "Metronome Sound",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlateText
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetronomeSound.entries.forEach { sound ->
                        val selected = state.metronomeSound == sound
                        FilterChip(
                            selected = selected,
                            onClick = { onMetronomeSoundSelected(sound) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("metro_sound_${sound.name.lowercase()}"),
                            label = {
                                Text(
                                    text = sound.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ConsoleSurface,
                                selectedContainerColor = WarmAmberContainer,
                                selectedLabelColor = WarmAmber
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = HardwareBorder,
                                selectedBorderColor = WarmAmber
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.alternate_mute_bar),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Plays 1 measure, mutes 1 measure to train internal pulse",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedSlateText
                        )
                    }
                    Switch(
                        checked = state.alternateMuteBar,
                        onCheckedChange = { onToggleMuteBar() },
                        modifier = Modifier.testTag("alternate_mute_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StudioCharcoal,
                            checkedTrackColor = WarmAmber
                        )
                    )
                }
            }
        }

        // 3. HD-300 5-Channel Subdivision Mixer
        ConsoleSectionCard(title = "5-CHANNEL SUBDIVISION MIXER") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Quick Groove Presets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickMixButton(
                        label = "Quarter",
                        onClick = {
                            onSubdivisionMixChanged {
                                SubdivisionMix(accent = 1f, quarter = 0.85f, eighth = 0f, triplet = 0f, sixteenth = 0f)
                            }
                        },
                        testTag = "mix_preset_quarter"
                    )
                    QuickMixButton(
                        label = "8th Groove",
                        onClick = {
                            onSubdivisionMixChanged {
                                SubdivisionMix(accent = 1f, quarter = 0.85f, eighth = 0.65f, triplet = 0f, sixteenth = 0f)
                            }
                        },
                        testTag = "mix_preset_eighth"
                    )
                    QuickMixButton(
                        label = "Triplet",
                        onClick = {
                            onSubdivisionMixChanged {
                                SubdivisionMix(accent = 1f, quarter = 0.85f, eighth = 0f, triplet = 0.65f, sixteenth = 0f)
                            }
                        },
                        testTag = "mix_preset_triplet"
                    )
                    QuickMixButton(
                        label = "16th Pulse",
                        onClick = {
                            onSubdivisionMixChanged {
                                SubdivisionMix(accent = 1f, quarter = 0.85f, eighth = 0.45f, triplet = 0f, sixteenth = 0.6f)
                            }
                        },
                        testTag = "mix_preset_sixteenth"
                    )
                }

                // 5 Hardware Vertical Faders
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubdivisionFader(
                        label = "ACC",
                        subLabel = "Beat 1",
                        value = state.subdivisionMix.accent,
                        accentColor = DownbeatCrimson,
                        onValueChange = { v -> onSubdivisionMixChanged { it.copy(accent = v) } },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fader_accent")
                    )
                    SubdivisionFader(
                        label = "1/4",
                        subLabel = "Beat",
                        value = state.subdivisionMix.quarter,
                        accentColor = PitchEmerald,
                        onValueChange = { v -> onSubdivisionMixChanged { it.copy(quarter = v) } },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fader_quarter")
                    )
                    SubdivisionFader(
                        label = "1/8",
                        subLabel = "Eighth",
                        value = state.subdivisionMix.eighth,
                        accentColor = ElectricSky,
                        onValueChange = { v -> onSubdivisionMixChanged { it.copy(eighth = v) } },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fader_eighth")
                    )
                    SubdivisionFader(
                        label = "TRIP",
                        subLabel = "Triplet",
                        value = state.subdivisionMix.triplet,
                        accentColor = WarmAmber,
                        onValueChange = { v -> onSubdivisionMixChanged { it.copy(triplet = v) } },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fader_triplet")
                    )
                    SubdivisionFader(
                        label = "1/16",
                        subLabel = "16th",
                        value = state.subdivisionMix.sixteenth,
                        accentColor = ElectricSky,
                        onValueChange = { v -> onSubdivisionMixChanged { it.copy(sixteenth = v) } },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fader_sixteenth")
                    )
                }
            }
        }
    }
}

@Composable
private fun ConductorPendulumGauge(
    isPlaying: Boolean,
    beatIndex: Int,
    isMutedBar: Boolean,
    modifier: Modifier = Modifier
) {
    val targetAngle = if (!isPlaying) 0f else if (beatIndex % 2 == 0) -34f else 34f
    val angle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pendulum_angle"
    )

    Surface(
        modifier = modifier,
        color = LcdMidnight,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, LcdBorder)
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val cx = size.width / 2f
            val pivotY = size.height * 0.85f
            val armLen = size.height * 0.72f

            // Arc background
            drawArc(
                color = HardwareBorder,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - armLen, pivotY - armLen),
                size = Size(armLen * 2, armLen * 2),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            // Pendulum rod
            val rad = Math.toRadians((angle - 90f).toDouble())
            val tipX = cx + (armLen * cos(rad)).toFloat()
            val tipY = pivotY + (armLen * sin(rad)).toFloat()

            val rodColor = when {
                !isPlaying -> MutedSlateText
                isMutedBar -> WarmAmber
                beatIndex == 0 -> DownbeatCrimson
                else -> ElectricSky
            }

            drawLine(
                color = rodColor,
                start = Offset(cx, pivotY),
                end = Offset(tipX, tipY),
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = rodColor,
                radius = 6.dp.toPx(),
                center = Offset(tipX, tipY)
            )
            drawCircle(
                color = HardwareBorder,
                radius = 4.dp.toPx(),
                center = Offset(cx, pivotY)
            )
        }
    }
}

@Composable
private fun SubdivisionFader(
    label: String,
    subLabel: String,
    value: Float,
    accentColor: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.medium)
            .background(ConsoleSurface)
            .border(1.dp, HardwareBorder, MaterialTheme.shapes.medium)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "${(value * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (value > 0.01f) accentColor else MutedSlateText
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val next = 1f - (offset.y / size.height.coerceAtLeast(1))
                        onValueChange(next.coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val next = 1f - (change.position.y / size.height.coerceAtLeast(1))
                        onValueChange(next.coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 6.dp.toPx()
                val trackX = (size.width - trackW) / 2f
                // Background groove
                drawRoundRect(
                    color = StudioCharcoal,
                    topLeft = Offset(trackX, 0f),
                    size = Size(trackW, size.height),
                    cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
                )
                // Active fill
                val fillH = size.height * value.coerceIn(0f, 1f)
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.85f),
                    topLeft = Offset(trackX, size.height - fillH),
                    size = Size(trackW, fillH),
                    cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
                )
                // Fader cap
                val capH = 14.dp.toPx()
                val capW = 28.dp.toPx().coerceAtMost(size.width)
                val capY = ((1f - value.coerceIn(0f, 1f)) * (size.height - capH))
                drawRoundRect(
                    color = Color(0xFFE2E8F0),
                    topLeft = Offset((size.width - capW) / 2f, capY),
                    size = Size(capW, capH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MutedSlateText
            )
        }
    }
}

@Composable
private fun QuickMixButton(
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .height(40.dp)
            .testTag(testTag),
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.dp, HardwareBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ConsoleSurface,
            contentColor = ElectricSky
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun tempoItalianMarking(bpm: Int): String = when {
    bpm < 48 -> "Larghissimo"
    bpm < 60 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 92 -> "Andante"
    bpm < 108 -> "Moderato"
    bpm < 120 -> "Allegretto"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}
