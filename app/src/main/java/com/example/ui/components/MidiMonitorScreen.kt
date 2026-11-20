package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.MetronomeBeatSnapshot
import com.example.midi.MidiConnectionUiState
import com.example.model.AudioOutputMode
import com.example.model.MetronomeSound
import com.example.model.NotePitchInfo
import com.example.model.PitchTheory
import com.example.model.TemperamentMode
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import com.example.ui.HarmonyUiState
import com.example.ui.MetronomeUiState
import com.example.ui.MidiControlPulse
import com.example.ui.MidiControlTarget
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceBright
import com.example.ui.theme.CrispOffWhite
import com.example.ui.theme.DownbeatCrimson
import com.example.ui.theme.EbonyKey
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.ElectricSkyContainer
import com.example.ui.theme.HardwareBorder
import com.example.ui.theme.IvoryKey
import com.example.ui.theme.IvoryKeyPressed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LcdBorder
import com.example.ui.theme.LcdMidnight
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.PitchEmerald
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.WarmAmberContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Dedicated zone accents matching the user's SMK-37 PRO blueprint
private val MetronomeYellowBorder = Color(0xFFFACC15)
private val MetronomePurpleBorder = Color(0xFFA855F7)
private val KeyboardRedBorder = Color(0xFFEF4444)
private val HarmonyGreenBorder = Color(0xFF10B981)

private data class KnobSlotModel(
    val target: MidiControlTarget?,
    val label: String,
    val cc: Int,
    val normalizedValue: Float,
    val valueText: String,
    val accentColor: Color
) {
    val isAssigned: Boolean get() = target != null && cc >= 0

    companion object {
        fun empty(slotNumber: Int) = KnobSlotModel(
            target = null,
            label = "K$slotNumber",
            cc = -1,
            normalizedValue = 0f,
            valueText = "",
            accentColor = HardwareBorder
        )
    }
}

private data class PadSlotModel(
    val target: MidiControlTarget?,
    val label: String,
    val cc: Int,
    val isToggle: Boolean,
    val isLit: Boolean,
    val litColor: Color,
    val offAccentColor: Color? = null,
    val statusTag: String? = null
) {
    val isAssigned: Boolean get() = target != null && cc >= 0

    companion object {
        fun empty() = PadSlotModel(
            target = null,
            label = "",
            cc = -1,
            isToggle = false,
            isLit = false,
            litColor = HardwareBorder
        )
    }
}

private data class FaderSlotModel(
    val target: MidiControlTarget?,
    val label: String,
    val cc: Int,
    val level: Float,
    val accentColor: Color
) {
    val isAssigned: Boolean get() = target != null && cc >= 0

    companion object {
        fun empty(slotCc: Int) = FaderSlotModel(
            target = null,
            label = "—",
            cc = slotCc,
            level = 0f,
            accentColor = HardwareBorder
        )
    }
}

private fun voiceShortTag(voice: ToneVoice): String = when (voice) {
    ToneVoice.PURE_SINE -> "SINE"
    ToneVoice.ORGAN -> "ORG"
    ToneVoice.BRASS -> "BRS"
    ToneVoice.WOODWIND -> "WIND"
    ToneVoice.STRINGS -> "STR"
    ToneVoice.PIANO -> "PNO"
}

private fun clickShortTag(sound: MetronomeSound): String = when (sound) {
    MetronomeSound.WOODBLOCK -> "WOOD"
    MetronomeSound.CLICK -> "CLK"
    MetronomeSound.BELL -> "BELL"
    MetronomeSound.RIMSHOT -> "RIM"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen, zero-scroll, touch-locked SMK-37 PRO hardware-faceplate MIDI Monitor Mode.
 * - Locks orientation to landscape while active and restores user orientation on exit.
 * - Keeps screen awake automatically and blocks Back gestures.
 * - Mirrors the exact M-VAVE SMK-37 PRO physical CC grid:
 *   Mod Wheel (CC #1), 8 Knobs (CC #48..#55), 8 Top Pads (CC #40..#47),
 *   8 Bottom Pads (CC #32..#39), 4 Faders (CC #64..#67),
 *   Yellow & Purple Metronome zones, Red 37-key Read-Only Keyboard, Green Harmony zone.
 * - Exits ONLY via press-and-hold (~0.8s) on the top-right HOLD EXIT button.
 */
@Composable
fun MidiMonitorScreen(
    harmonyState: HarmonyUiState,
    metronomeState: MetronomeUiState,
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    midiState: MidiConnectionUiState,
    midiMappings: Map<MidiControlTarget, Int>,
    lastControlPulse: MidiControlPulse,
    sustainPedalDown: Boolean,
    audioOutputMode: AudioOutputMode,
    onExitMonitor: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Auto-lock landscape while in Monitor Mode without triggering a flip-flop on config change
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (activity != null && !activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Keep screen awake automatically on music stand
    val view = LocalView.current
    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    // Block accidental hardware/gesture Back navigation while locked
    BackHandler(enabled = true) {}

    // Brief visual flash (~280ms) on whichever knob, pad, or fader just received MIDI CC
    var pulsedTarget by remember { mutableStateOf<MidiControlTarget?>(null) }
    LaunchedEffect(lastControlPulse.seq) {
        if (lastControlPulse.seq == 0L) return@LaunchedEffect
        pulsedTarget = lastControlPulse.target
        delay(280L)
        pulsedTarget = null
    }

    fun mappedCc(target: MidiControlTarget): Int = midiMappings[target] ?: target.defaultCc

    val mix = metronomeState.subdivisionMix
    val isPure = harmonyState.temperament == TemperamentMode.PURE

    // Lookup active target bound to each physical SMK-37 PRO hardware CC number
    val targetByCc = buildMap<Int, MidiControlTarget> {
        MidiControlTarget.entries.forEach { target ->
            val cc = mappedCc(target)
            if (cc >= 0 && cc !in this) {
                put(cc, target)
            }
        }
    }

    fun continuousNormalized(target: MidiControlTarget): Float = when (target) {
        MidiControlTarget.BPM -> ((metronomeState.bpm - 32f) / (280f - 32f)).coerceIn(0f, 1f)
        MidiControlTarget.TONE_VOLUME -> harmonyState.toneGain.coerceIn(0f, 1f)
        MidiControlTarget.METRONOME_VOLUME -> metronomeState.metronomeGain.coerceIn(0f, 1f)
        MidiControlTarget.ACCENT_LEVEL -> mix.accent.coerceIn(0f, 1f)
        MidiControlTarget.QUARTER_LEVEL -> mix.quarter.coerceIn(0f, 1f)
        MidiControlTarget.EIGHTH_LEVEL -> mix.eighth.coerceIn(0f, 1f)
        MidiControlTarget.TRIPLET_LEVEL -> mix.triplet.coerceIn(0f, 1f)
        MidiControlTarget.SIXTEENTH_LEVEL -> mix.sixteenth.coerceIn(0f, 1f)
        MidiControlTarget.METER_TOP_VALUE -> (
            (metronomeState.timeSignature.numerator - TimeSignature.MIN_NUMERATOR).toFloat() /
                (TimeSignature.MAX_NUMERATOR - TimeSignature.MIN_NUMERATOR).toFloat()
            ).coerceIn(0f, 1f)
        MidiControlTarget.METER_BOTTOM_VALUE -> {
            val allowed = TimeSignature.ALLOWED_DENOMINATORS
            val idx = allowed.indexOf(metronomeState.timeSignature.denominator).coerceAtLeast(0)
            (idx.toFloat() / allowed.lastIndex.toFloat()).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    fun continuousLabel(target: MidiControlTarget): String = when (target) {
        MidiControlTarget.BPM -> "Tempo"
        MidiControlTarget.TONE_VOLUME -> "Tone Vol"
        MidiControlTarget.METRONOME_VOLUME -> "Metro Vol"
        MidiControlTarget.ACCENT_LEVEL -> "Accent"
        MidiControlTarget.QUARTER_LEVEL -> "1/4"
        MidiControlTarget.EIGHTH_LEVEL -> "1/8"
        MidiControlTarget.TRIPLET_LEVEL -> "Trip"
        MidiControlTarget.SIXTEENTH_LEVEL -> "1/16"
        MidiControlTarget.METER_TOP_VALUE -> "Meter Top"
        MidiControlTarget.METER_BOTTOM_VALUE -> "Meter Bot"
        else -> target.label
    }

    fun continuousValueText(target: MidiControlTarget): String = when (target) {
        MidiControlTarget.BPM -> "${metronomeState.bpm}"
        MidiControlTarget.METER_TOP_VALUE -> "${metronomeState.timeSignature.numerator}"
        MidiControlTarget.METER_BOTTOM_VALUE -> "/${metronomeState.timeSignature.denominator}"
        else -> "${(continuousNormalized(target) * 100f).roundToInt()}%"
    }

    fun continuousAccent(target: MidiControlTarget): Color = when (target) {
        MidiControlTarget.BPM -> if (metronomeState.isPlaying) PitchEmerald else ElectricSky
        MidiControlTarget.METRONOME_VOLUME, MidiControlTarget.TRIPLET_LEVEL -> PitchEmerald
        MidiControlTarget.ACCENT_LEVEL,
        MidiControlTarget.METER_TOP_VALUE,
        MidiControlTarget.METER_BOTTOM_VALUE -> WarmAmber
        else -> ElectricSky
    }

    fun buildKnobModel(target: MidiControlTarget, cc: Int) = KnobSlotModel(
        target = target,
        label = continuousLabel(target),
        cc = cc,
        normalizedValue = continuousNormalized(target),
        valueText = continuousValueText(target),
        accentColor = continuousAccent(target)
    )

    fun buildFaderModel(slotCc: Int): FaderSlotModel {
        val target = targetByCc[slotCc]
        if (target == null || target.isTrigger) return FaderSlotModel.empty(slotCc)
        return FaderSlotModel(
            target = target,
            label = continuousLabel(target),
            cc = slotCc,
            level = continuousNormalized(target),
            accentColor = continuousAccent(target)
        )
    }

    fun buildPadModel(target: MidiControlTarget, cc: Int): PadSlotModel = when (target) {
        MidiControlTarget.TOGGLE_METRONOME -> PadSlotModel(
            target = target,
            label = "Play/Stop",
            cc = cc,
            isToggle = true,
            isLit = metronomeState.isPlaying,
            litColor = PitchEmerald,
            statusTag = if (metronomeState.isPlaying) "ON" else "OFF"
        )
        MidiControlTarget.TOGGLE_CHORD_HOLD -> PadSlotModel(
            target = target,
            label = "Hold",
            cc = cc,
            isToggle = true,
            isLit = harmonyState.chordHoldEnabled,
            litColor = ElectricSky,
            statusTag = if (harmonyState.chordHoldEnabled) "ON" else "OFF"
        )
        MidiControlTarget.TOGGLE_TEMPERAMENT -> PadSlotModel(
            target = target,
            label = "Pure/Eq",
            cc = cc,
            isToggle = true,
            isLit = isPure,
            litColor = PitchEmerald,
            offAccentColor = WarmAmber,
            statusTag = if (isPure) "PURE" else "EQ"
        )
        MidiControlTarget.TAP_TEMPO -> PadSlotModel(
            target = target,
            label = "Tap",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = WarmAmber
        )
        MidiControlTarget.CLEAR_HELD -> PadSlotModel(
            target = target,
            label = "Clear",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = ElectricSky
        )
        MidiControlTarget.MUTE_ALL -> PadSlotModel(
            target = target,
            label = "All Off",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = DownbeatCrimson,
            offAccentColor = DownbeatCrimson
        )
        MidiControlTarget.NEXT_TONE_VOICE -> PadSlotModel(
            target = target,
            label = "Voice",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = ElectricSky,
            statusTag = voiceShortTag(harmonyState.voice)
        )
        MidiControlTarget.NEXT_METRONOME_SOUND -> PadSlotModel(
            target = target,
            label = "Click",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = WarmAmber,
            statusTag = clickShortTag(metronomeState.metronomeSound)
        )
        MidiControlTarget.METER_TOP_STEP -> PadSlotModel(
            target = target,
            label = "Top +1",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = WarmAmber
        )
        MidiControlTarget.METER_BOTTOM_STEP -> PadSlotModel(
            target = target,
            label = "Bot +",
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = WarmAmber
        )
        else -> PadSlotModel(
            target = target,
            label = target.label,
            cc = cc,
            isToggle = false,
            isLit = false,
            litColor = ElectricSky
        )
    }

    // Physical Mod Wheel (CC #1) & 4 Right Faders (CC #64..#67)
    val modWheelFader = buildFaderModel(1)
    val rightFaders = listOf(64, 65, 66, 67).map { buildFaderModel(it) }

    // Physical 8 Rotary Knobs (CC #48..#55) + fallback slots for any continuous target mapped outside standard CCs
    val standardContinuousCcs = setOf(1, 48, 49, 50, 51, 52, 53, 54, 55, 64, 65, 66, 67)
    val customContinuousQueue = ArrayDeque(
        MidiControlTarget.entries
            .filter { !it.isTrigger }
            .mapNotNull { t ->
                val cc = mappedCc(t)
                if (cc >= 0 && cc !in standardContinuousCcs) buildKnobModel(t, cc) else null
            }
    )
    val knobs = List(8) { index ->
        val slotCc = 48 + index
        val bound = targetByCc[slotCc]
        when {
            bound != null && !bound.isTrigger -> buildKnobModel(bound, slotCc)
            customContinuousQueue.isNotEmpty() -> customContinuousQueue.removeFirst()
            else -> KnobSlotModel.empty(index + 1)
        }
    }

    // Physical 16 Pads: Top Row (CC #40..#47), Bottom Row (CC #32..#39) + fallback for custom trigger CCs
    val standardPadCcs = (32..47).toSet()
    val customTriggerQueue = ArrayDeque(
        MidiControlTarget.entries
            .filter { it.isTrigger }
            .mapNotNull { t ->
                val cc = mappedCc(t)
                if (cc >= 0 && cc !in standardPadCcs) buildPadModel(t, cc) else null
            }
    )
    val topPads = List(8) { index ->
        val slotCc = 40 + index
        val bound = targetByCc[slotCc]
        when {
            bound != null && bound.isTrigger -> buildPadModel(bound, slotCc)
            customTriggerQueue.isNotEmpty() -> customTriggerQueue.removeFirst()
            else -> PadSlotModel.empty()
        }
    }
    val bottomPads = List(8) { index ->
        val slotCc = 32 + index
        val bound = targetByCc[slotCc]
        when {
            bound != null && bound.isTrigger -> buildPadModel(bound, slotCc)
            customTriggerQueue.isNotEmpty() -> customTriggerQueue.removeFirst()
            else -> PadSlotModel.empty()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("midi_monitor_screen"),
        color = StudioCharcoal
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            val isCompactHeight = maxHeight < 430.dp
            val isWideScreen = maxWidth >= 880.dp
            val showLastEvent = maxWidth >= 580.dp
            val gap = if (isCompactHeight) 5.dp else 8.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                // 1. Slim Top Lock & MIDI Telemetry Bar
                MonitorTopBar(
                    midiState = midiState,
                    audioOutputMode = audioOutputMode,
                    isCompactHeight = isCompactHeight,
                    showLastEvent = showLastEvent,
                    onExitMonitor = onExitMonitor
                )

                // 2. TOP DECK: SMK-37 PRO Hardware Faceplate (Mod Wheel + Yellow | 8x3 Knobs & Pads | 4 Faders + Purple)
                Surface(
                    modifier = Modifier
                        .weight(1.24f)
                        .fillMaxWidth(),
                    color = AnodizedSlate,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.2.dp, HardwareBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isCompactHeight) 6.dp else 10.dp, vertical = if (isCompactHeight) 5.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 6.dp else 10.dp)
                    ) {
                        // Far-Left Column: Mod Wheel CC #1 (Top) + Yellow Metronome Primary Box (Bottom)
                        Column(
                            modifier = Modifier
                                .weight(1.30f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            HardwareFaderSlot(
                                model = modWheelFader,
                                isPulsing = modWheelFader.target != null && pulsedTarget == modWheelFader.target,
                                isCompactHeight = isCompactHeight,
                                modifier = Modifier
                                    .weight(1.55f)
                                    .fillMaxWidth()
                            )
                            MetronomeYellowBox(
                                metronomeState = metronomeState,
                                isCompactHeight = isCompactHeight,
                                modifier = Modifier
                                    .weight(1.05f)
                                    .fillMaxWidth()
                            )
                        }

                        // Center 8-Column Matrix: 8 Rotary Knobs (48..55) + 8 Top Pads (40..47) + 8 Bottom Pads (32..39)
                        Column(
                            modifier = Modifier
                                .weight(7.15f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 3.dp else 6.dp)
                        ) {
                            // Row 1: 8 Rotary Knobs (48..55)
                            Row(
                                modifier = Modifier
                                    .weight(1.08f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp)
                            ) {
                                knobs.forEach { knob ->
                                    HardwareKnobSlot(
                                        model = knob,
                                        isPulsing = knob.target != null && pulsedTarget == knob.target,
                                        isCompactHeight = isCompactHeight,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }

                            // Row 2: Top 8 Pads (40..47)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp)
                            ) {
                                topPads.forEach { pad ->
                                    HardwarePadSlot(
                                        model = pad,
                                        isPulsing = pad.target != null && pulsedTarget == pad.target,
                                        isCompactHeight = isCompactHeight,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }

                            // Row 3: Bottom 8 Pads (32..39)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp)
                            ) {
                                bottomPads.forEach { pad ->
                                    HardwarePadSlot(
                                        model = pad,
                                        isPulsing = pad.target != null && pulsedTarget == pad.target,
                                        isCompactHeight = isCompactHeight,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }

                        // Far-Right Block: 4 Vertical Faders (64..67) + Purple Metronome Status Box
                        Column(
                            modifier = Modifier
                                .weight(3.55f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1.55f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp)
                            ) {
                                rightFaders.forEach { fader ->
                                    HardwareFaderSlot(
                                        model = fader,
                                        isPulsing = fader.target != null && pulsedTarget == fader.target,
                                        isCompactHeight = isCompactHeight,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }

                            MetronomePurpleBox(
                                metronomeState = metronomeState,
                                beatSnapshotFlow = beatSnapshotFlow,
                                isCompactHeight = isCompactHeight,
                                modifier = Modifier
                                    .weight(1.05f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }

                // 3. BOTTOM DECK: Red Read-Only 37-Key Visual Keyboard (Left) + Green Harmony Telemetry Panel (Right)
                Row(
                    modifier = Modifier
                        .weight(1.0f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    MonitorKeyboard37Card(
                        harmonyState = harmonyState,
                        isCompactHeight = isCompactHeight,
                        modifier = Modifier
                            .weight(1.42f)
                            .fillMaxHeight()
                    )

                    HarmonyGreenBox(
                        harmonyState = harmonyState,
                        sustainPedalDown = sustainPedalDown,
                        isCompactHeight = isCompactHeight,
                        isWideScreen = isWideScreen,
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorTopBar(
    midiState: MidiConnectionUiState,
    audioOutputMode: AudioOutputMode,
    isCompactHeight: Boolean,
    showLastEvent: Boolean,
    onExitMonitor: () -> Unit
) {
    val isMidiConnected = midiState.connectedDeviceName != null
    val barHeight = if (isCompactHeight) 28.dp else 34.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Lock & Hardware badge
        Surface(
            color = ElectricSky.copy(alpha = 0.14f),
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(1.dp, ElectricSky.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = ElectricSky,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "SMK-37 PRO MONITOR",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElectricSky,
                    maxLines = 1
                )
            }
        }

        // Connected MIDI device + live event telemetry strip (read-only)
        Surface(
            color = LcdMidnight,
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(
                1.dp,
                if (isMidiConnected) PitchEmerald.copy(alpha = 0.6f) else WarmAmber.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isMidiConnected) PitchEmerald else WarmAmber)
                )
                Text(
                    text = midiState.connectedDeviceName ?: "NO MIDI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isMidiConnected) PitchEmerald else WarmAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (showLastEvent) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlateText
                    )
                    Icon(
                        imageVector = Icons.Filled.Sensors,
                        contentDescription = null,
                        tint = ElectricSky.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = midiState.lastEventText,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricSky,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Read-only Audio Output Mode badge
        MonitorOutputModeBadge(
            audioOutputMode = audioOutputMode,
            modifier = Modifier.fillMaxHeight()
        )

        // Only interactive control on screen: press-and-hold unlock
        HoldToExitButton(
            onExitConfirmed = onExitMonitor,
            modifier = Modifier.fillMaxHeight()
        )
    }
}

@Composable
private fun HoldToExitButton(
    onExitConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Long = 800L
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val currentOnExit by rememberUpdatedState(onExitConfirmed)
    val haptic = LocalHapticFeedback.current
    val cdText = stringResource(R.string.cd_hold_exit_monitor)

    LaunchedEffect(isHolding) {
        if (!isHolding) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val startNanos = System.nanoTime()
        val durationNanos = holdDurationMs * 1_000_000L
        while (isHolding) {
            val elapsed = System.nanoTime() - startNanos
            val fraction = (elapsed.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
            holdProgress = fraction
            if (fraction >= 1f) {
                isHolding = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnExit()
                break
            }
            delay(16L)
        }
    }

    val borderColor by animateColorAsState(
        targetValue = if (isHolding) WarmAmber else HardwareBorder,
        animationSpec = tween(100),
        label = "hold_exit_border"
    )

    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = ConsoleSurface,
        border = BorderStroke(1.2.dp, borderColor),
        modifier = modifier
            .width(96.dp)
            .semantics { contentDescription = cdText }
            .testTag("monitor_hold_to_exit_button")
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isHolding = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        isHolding = false
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (holdProgress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(holdProgress)
                        .background(WarmAmber.copy(alpha = 0.45f))
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isHolding) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (isHolding) CrispOffWhite else WarmAmber,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (isHolding) {
                        stringResource(R.string.monitor_holding_exit)
                    } else {
                        stringResource(R.string.monitor_hold_to_exit)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isHolding) CrispOffWhite else WarmAmber,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HardwareKnobSlot(
    model: KnobSlotModel,
    isPulsing: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedNorm by animateFloatAsState(
        targetValue = model.normalizedValue.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 70),
        label = "knob_norm_${model.label}"
    )
    val activeAccent by animateColorAsState(
        targetValue = when {
            !model.isAssigned -> HardwareBorder.copy(alpha = 0.55f)
            isPulsing -> CrispOffWhite
            else -> model.accentColor
        },
        animationSpec = tween(durationMillis = 120),
        label = "knob_accent_${model.label}"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = if (model.isAssigned) model.label else "",
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (isCompactHeight) 8.sp else 10.sp,
            lineHeight = if (isCompactHeight) 9.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isPulsing) CrispOffWhite else if (model.isAssigned) ElectricSky else MutedSlateText.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val diameter = minOf(size.width, size.height) * 0.90f
                val radius = diameter / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(diameter, diameter)
                val strokePx = if (isCompactHeight) 2.5.dp.toPx() else 3.2.dp.toPx()

                // Recessed knob body
                drawCircle(
                    color = if (model.isAssigned) LcdMidnight else StudioCharcoal.copy(alpha = 0.7f),
                    radius = radius - strokePx * 0.6f,
                    center = center
                )

                // Pulse halo while knob is actively turned
                if (isPulsing && model.isAssigned) {
                    drawCircle(
                        color = model.accentColor.copy(alpha = 0.28f),
                        radius = radius + 2.dp.toPx(),
                        center = center
                    )
                }

                // 270-degree background dial track
                drawArc(
                    color = HardwareBorder.copy(alpha = if (model.isAssigned) 0.75f else 0.4f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                if (model.isAssigned) {
                    // Active value arc
                    drawArc(
                        color = activeAccent,
                        startAngle = 135f,
                        sweepAngle = (270f * animatedNorm).coerceAtLeast(4f),
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )

                    // Pointer tick on outer rim
                    val angleRad = (135.0 + 270.0 * animatedNorm) * (PI / 180.0)
                    val innerR = radius * 0.52f
                    val outerR = radius * 0.92f
                    drawLine(
                        color = CrispOffWhite,
                        start = Offset(
                            center.x + (cos(angleRad) * innerR).toFloat(),
                            center.y + (sin(angleRad) * innerR).toFloat()
                        ),
                        end = Offset(
                            center.x + (cos(angleRad) * outerR).toFloat(),
                            center.y + (sin(angleRad) * outerR).toFloat()
                        ),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            if (model.isAssigned) {
                Text(
                    text = "${model.cc}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCompactHeight) 9.sp else 11.sp,
                    color = if (isPulsing) CrispOffWhite else PitchEmerald,
                    maxLines = 1
                )
            }
        }

        Text(
            text = if (model.isAssigned) model.valueText else "—",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = if (isCompactHeight) 8.sp else 10.sp,
            lineHeight = if (isCompactHeight) 9.sp else 11.sp,
            color = if (model.isAssigned) activeAccent else MutedSlateText.copy(alpha = 0.28f),
            maxLines = 1
        )
    }
}

@Composable
private fun HardwarePadSlot(
    model: PadSlotModel,
    isPulsing: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val isActiveGlow = model.isAssigned && (model.isLit || isPulsing)

    val containerTarget = when {
        !model.isAssigned -> StudioCharcoal.copy(alpha = 0.6f)
        isPulsing -> model.litColor.copy(alpha = 0.45f)
        model.isLit -> model.litColor.copy(alpha = 0.28f)
        else -> LcdMidnight
    }
    val borderTarget = when {
        !model.isAssigned -> HardwareBorder.copy(alpha = 0.42f)
        isPulsing -> CrispOffWhite
        model.isLit -> model.litColor
        model.offAccentColor != null -> model.offAccentColor.copy(alpha = 0.55f)
        else -> HardwareBorder
    }
    val containerColor by animateColorAsState(
        targetValue = containerTarget,
        animationSpec = tween(durationMillis = 100),
        label = "pad_bg_${model.cc}"
    )
    val borderColor by animateColorAsState(
        targetValue = borderTarget,
        animationSpec = tween(durationMillis = 100),
        label = "pad_border_${model.cc}"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = if (model.isAssigned) model.label else "",
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (isCompactHeight) 8.sp else 10.sp,
            lineHeight = if (isCompactHeight) 9.sp else 11.sp,
            fontWeight = if (isActiveGlow) FontWeight.Bold else FontWeight.Medium,
            color = when {
                !model.isAssigned -> Color.Transparent
                isActiveGlow -> model.litColor
                else -> MutedSlateText
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(if (model.isAssigned) "monitor_pad_cc_${model.cc}" else "monitor_pad_empty"),
            color = containerColor,
            shape = RoundedCornerShape(if (isCompactHeight) 5.dp else 7.dp),
            border = BorderStroke(if (isActiveGlow) 1.6.dp else 1.dp, borderColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 3.dp, vertical = 2.dp)
            ) {
                if (model.isAssigned) {
                    // Top-right hardware LED lamp (lights up bright on 40 / 41 / 42 when ON, or flashes on trigger)
                    val ledColor = when {
                        isPulsing -> CrispOffWhite
                        model.isLit -> model.litColor
                        model.offAccentColor != null && model.isToggle -> model.offAccentColor.copy(alpha = 0.75f)
                        else -> HardwareBorder.copy(alpha = 0.65f)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(1.dp)
                            .size(if (isCompactHeight) 5.dp else 6.dp)
                            .clip(CircleShape)
                            .background(ledColor)
                    )

                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${model.cc}",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompactHeight) 11.sp else 14.sp,
                            lineHeight = if (isCompactHeight) 12.sp else 15.sp,
                            color = when {
                                isActiveGlow -> CrispOffWhite
                                model.offAccentColor != null -> model.offAccentColor
                                else -> PitchEmerald
                            },
                            maxLines = 1
                        )
                        model.statusTag?.let { tag ->
                            Text(
                                text = tag,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompactHeight) 7.sp else 9.sp,
                                lineHeight = if (isCompactHeight) 8.sp else 10.sp,
                                color = when {
                                    model.isLit -> model.litColor
                                    model.offAccentColor != null -> model.offAccentColor
                                    else -> MutedSlateText.copy(alpha = 0.75f)
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareFaderSlot(
    model: FaderSlotModel,
    isPulsing: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val clamped = model.level.coerceIn(0f, 1f)
    val animatedLevel by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 70),
        label = "fader_level_${model.label}"
    )
    val percent = (clamped * 100f).roundToInt()
    val isActive = model.isAssigned && clamped > 0.005f
    val borderColor by animateColorAsState(
        targetValue = when {
            !model.isAssigned -> HardwareBorder.copy(alpha = 0.42f)
            isPulsing -> CrispOffWhite
            isActive -> model.accentColor.copy(alpha = 0.55f)
            else -> HardwareBorder.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 100),
        label = "fader_border_${model.label}"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // CC number + function name at top (matching user's sketch)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (model.cc >= 0) {
                Text(
                    text = "${model.cc}",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCompactHeight) 8.sp else 10.sp,
                    color = if (model.isAssigned) PitchEmerald else MutedSlateText.copy(alpha = 0.4f),
                    maxLines = 1
                )
            }
            Text(
                text = model.label,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (isCompactHeight) 8.sp else 10.sp,
                color = when {
                    !model.isAssigned -> MutedSlateText.copy(alpha = 0.4f)
                    isPulsing || isActive -> CrispOffWhite
                    else -> MutedSlateText
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Recessed hardware vertical fader track with glowing fill and sliding thumb cap
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            color = if (model.isAssigned) LcdMidnight else StudioCharcoal.copy(alpha = 0.65f),
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(if (isPulsing) 1.5.dp else 1.dp, borderColor)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 5.dp)
            ) {
                val w = size.width
                val h = size.height
                val centerX = w / 2f
                val thumbH = (if (isCompactHeight) 7.dp else 10.dp).toPx()
                val travelTop = thumbH / 2f
                val travelBottom = (h - thumbH / 2f).coerceAtLeast(travelTop + 1f)
                val travelSpan = travelBottom - travelTop
                val thumbY = travelBottom - (animatedLevel * travelSpan)

                // Scale ticks along sides
                val tickColor = HardwareBorder.copy(alpha = 0.5f)
                for (frac in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    val ty = travelBottom - frac * travelSpan
                    drawLine(
                        color = tickColor,
                        start = Offset(0f, ty),
                        end = Offset(w * 0.24f, ty),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = tickColor,
                        start = Offset(w * 0.76f, ty),
                        end = Offset(w, ty),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Center slot groove
                val slotW = 3.5.dp.toPx()
                drawRoundRect(
                    color = StudioCharcoal,
                    topLeft = Offset(centerX - slotW / 2f, travelTop),
                    size = Size(slotW, travelSpan),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Glowing active level bar inside slot
                if (model.isAssigned && animatedLevel > 0.005f) {
                    drawRoundRect(
                        color = model.accentColor,
                        topLeft = Offset(centerX - slotW / 2f, thumbY),
                        size = Size(slotW, (travelBottom - thumbY).coerceAtLeast(2.dp.toPx())),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }

                // Sliding hardware fader cap/thumb
                val thumbW = (w * 0.78f).coerceAtLeast(10.dp.toPx())
                drawRoundRect(
                    color = if (isPulsing) model.accentColor else ConsoleSurfaceBright,
                    topLeft = Offset(centerX - thumbW / 2f, thumbY - thumbH / 2f),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())
                )
                drawLine(
                    color = if (isActive || isPulsing) CrispOffWhite else MutedSlateText,
                    start = Offset(centerX - thumbW * 0.34f, thumbY),
                    end = Offset(centerX + thumbW * 0.34f, thumbY),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Percentage readout
        Text(
            text = if (model.isAssigned) "$percent%" else "—",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = if (isCompactHeight) 8.sp else 10.sp,
            lineHeight = if (isCompactHeight) 9.sp else 11.sp,
            color = if (isActive) model.accentColor else MutedSlateText.copy(alpha = 0.5f),
            maxLines = 1
        )
    }
}

/**
 * Yellow Zone (bottom-left under Fader CC #1): Core Metronome BPM + Time Signature + Play/Stop status.
 */
@Composable
private fun MetronomeYellowBox(
    metronomeState: MetronomeUiState,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val isPlaying = metronomeState.isPlaying
    Surface(
        modifier = modifier.testTag("monitor_metronome_yellow_box"),
        color = LcdMidnight,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.5.dp, MetronomeYellowBorder.copy(alpha = if (isPlaying) 0.95f else 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = if (isCompactHeight) 3.dp else 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) PitchEmerald else MutedSlateText)
                )
                Text(
                    text = if (isPlaying) "PLAY · BPM" else "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (isCompactHeight) 7.5.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPlaying) PitchEmerald else MutedSlateText,
                    maxLines = 1
                )
            }

            Text(
                text = "${metronomeState.bpm}",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = if (isCompactHeight) 19.sp else 26.sp,
                    lineHeight = if (isCompactHeight) 20.sp else 28.sp
                ),
                color = if (isPlaying) PitchEmerald else MetronomeYellowBorder,
                maxLines = 1
            )

            Surface(
                color = ConsoleSurface,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(0.8.dp, MetronomeYellowBorder.copy(alpha = 0.65f))
            ) {
                Text(
                    text = metronomeState.timeSignature.label,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCompactHeight) 10.sp else 13.sp,
                    color = CrispOffWhite,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * Purple Zone (bottom-right under Faders 64..67): Metronome Click Sound, Master Volume,
 * 1/1 Mute Bar alert, and isolated Large Beat LED Strip.
 */
@Composable
private fun MetronomePurpleBox(
    metronomeState: MetronomeUiState,
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val metroPercent = (metronomeState.metronomeGain.coerceIn(0f, 1f) * 100f).roundToInt()

    Surface(
        modifier = modifier.testTag("monitor_metronome_purple_box"),
        color = LcdMidnight,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.5.dp, MetronomePurpleBorder.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp, vertical = if (isCompactHeight) 4.dp else 6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top strip: Metronome Sound + Metro Master Vol bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = WarmAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(0.8.dp, WarmAmber.copy(alpha = 0.6f)),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Text(
                        text = metronomeState.metronomeSound.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (isCompactHeight) 8.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarmAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Metro Master Volume mini meter + percentage
                Row(
                    modifier = Modifier.weight(0.95f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "VOL",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (isCompactHeight) 7.5.sp else 9.sp,
                        color = MutedSlateText,
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (isCompactHeight) 6.dp else 8.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(ConsoleSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(metronomeState.metronomeGain.coerceIn(0f, 1f))
                                .background(PitchEmerald)
                        )
                    }
                    Text(
                        text = "$metroPercent%",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompactHeight) 8.sp else 10.sp,
                        color = PitchEmerald,
                        maxLines = 1
                    )
                }
            }

            // Bottom strip: Isolated large Beat LED bar + 1/1 Mute Bar status
            MonitorBeatLedBar(
                beatSnapshotFlow = beatSnapshotFlow,
                fallbackTotalBeats = metronomeState.timeSignature.beatsPerMeasure,
                alternateMuteBar = metronomeState.alternateMuteBar,
                isCompactHeight = isCompactHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isCompactHeight) 16.dp else 22.dp)
            )
        }
    }
}

/**
 * Isolated Beat LED Bar that collects [beatSnapshotFlow] without recomposing parent controls.
 */
@Composable
private fun MonitorBeatLedBar(
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    fallbackTotalBeats: Int,
    alternateMuteBar: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val beatSnap by beatSnapshotFlow.collectAsStateWithLifecycle()
    val ledDesc = stringResource(R.string.cd_beat_led)
    val total = (if (beatSnap.isPlaying) beatSnap.totalBeats else fallbackTotalBeats).coerceIn(1, 16)
    val segmentSpacing = if (total > 10) 2.dp else 3.dp

    Row(
        modifier = modifier.semantics { contentDescription = ledDesc },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(segmentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (b in 0 until total) {
                val isCurrent = beatSnap.isPlaying && beatSnap.beatIndex == b
                val targetColor = when {
                    !isCurrent && b == 0 -> DownbeatCrimson.copy(alpha = 0.24f)
                    !isCurrent -> ConsoleSurface
                    beatSnap.isMutedBar -> WarmAmber
                    b == 0 -> DownbeatCrimson
                    else -> PitchEmerald
                }
                val ledColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(durationMillis = 60),
                    label = "monitor_beat_led_$b"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(ledColor)
                )
            }
        }

        if (alternateMuteBar) {
            val isMutedNow = beatSnap.isPlaying && beatSnap.isMutedBar
            Surface(
                color = if (isMutedNow) WarmAmber else WarmAmberContainer,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, WarmAmber)
            ) {
                Text(
                    text = if (isMutedNow) "MUTED" else "1/1",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (isCompactHeight) 7.5.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isMutedNow) StudioCharcoal else WarmAmber,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * Red Zone (bottom-left): Read-only 37-key SMK-37 PRO visual keyboard monitor with
 * Auto-Octave follow and Pure Just Intonation Root highlighting.
 */
@Composable
private fun MonitorKeyboard37Card(
    harmonyState: HarmonyUiState,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val activeMidis = harmonyState.activeWrittenMidis
    val isPure = harmonyState.temperament == TemperamentMode.PURE
    val pitchInfoByWritten = remember(harmonyState.activePitchInfos) {
        harmonyState.activePitchInfos.associateBy { it.writtenMidi }
    }

    // Default 37-key SMK-37 PRO window is C3 (48) to C6 (84); auto-shifts in clean C-octaves if played outside range
    val startMidiC = remember(activeMidis) {
        if (activeMidis.isEmpty()) {
            48
        } else {
            val minNote = activeMidis.min()
            val maxNote = activeMidis.max()
            if (minNote >= 48 && maxNote <= 84) {
                48
            } else {
                ((minNote / 12) * 12).coerceIn(24, 72)
            }
        }
    }
    val endMidiC = startMidiC + 36
    val hasBelowRange = activeMidis.any { it < startMidiC }
    val hasAboveRange = activeMidis.any { it > endMidiC }

    Surface(
        modifier = modifier.testTag("monitor_keyboard_red_box"),
        color = AnodizedSlate,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, KeyboardRedBorder.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = if (isCompactHeight) 4.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 3.dp else 5.dp)
        ) {
            // Compact keyboard header strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "KEYBOARD · ${PitchTheory.noteNameWithOctave(startMidiC)}–${PitchTheory.noteNameWithOctave(endMidiC)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (isCompactHeight) 8.5.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CrispOffWhite,
                        maxLines = 1
                    )
                    if (hasBelowRange) {
                        Text(
                            text = "◀ LOW",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmAmber
                        )
                    }
                    if (hasAboveRange) {
                        Text(
                            text = "HIGH ▶",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmAmber
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isPure) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(PitchEmerald)
                            )
                            Text(
                                text = "ROOT",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = if (isCompactHeight) 7.5.sp else 9.sp,
                                color = PitchEmerald
                            )
                        }
                    }
                    Surface(
                        color = if (activeMidis.isNotEmpty()) ElectricSkyContainer else ConsoleSurface,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(
                            0.8.dp,
                            if (activeMidis.isNotEmpty()) ElectricSky.copy(alpha = 0.7f) else HardwareBorder
                        )
                    ) {
                        Text(
                            text = if (activeMidis.isEmpty()) "READ-ONLY" else "${activeMidis.size} ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = if (isCompactHeight) 7.5.sp else 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeMidis.isNotEmpty()) ElectricSky else MutedSlateText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Strictly read-only 37-key visual piano (22 white keys + 15 black keys)
            MonitorPianoKeyboard37(
                startMidiC = startMidiC,
                activeWrittenMidis = activeMidis,
                pitchInfoByWritten = pitchInfoByWritten,
                isPureMode = isPure,
                isCompactHeight = isCompactHeight,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MonitorPianoKeyboard37(
    startMidiC: Int,
    activeWrittenMidis: Set<Int>,
    pitchInfoByWritten: Map<Int, NotePitchInfo>,
    isPureMode: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    // 3 full octaves (C..B x 3 = 21 white) + final top C (1 white) = 22 white + 15 black = 37 keys
    val whiteOffsetsPerOctave = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val whiteMidis = remember(startMidiC) {
        buildList(22) {
            for (oct in 0 until 3) {
                for (semi in whiteOffsetsPerOctave) {
                    add(startMidiC + oct * 12 + semi)
                }
            }
            add(startMidiC + 36) // Top C
        }
    }
    // Black key semitone offset -> white-key boundary index within octave (between white 0|1, 1|2, 3|4, 4|5, 5|6)
    val blackSpecsPerOctave = remember {
        listOf(
            1 to 1,
            3 to 2,
            6 to 4,
            8 to 5,
            10 to 6
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StudioCharcoal)
            .border(1.dp, HardwareBorder, RoundedCornerShape(6.dp))
            .padding(2.dp)
    ) {
        val whiteKeyGap = 1.dp
        val whiteKeyPitch = (maxWidth + whiteKeyGap) / 22f
        val blackKeyWidth = whiteKeyPitch * 0.64f
        val blackKeyHeight = maxHeight * 0.58f

        // White keys layer (22 keys, strictly non-interactive)
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(whiteKeyGap)
        ) {
            whiteMidis.forEach { midi ->
                val isActive = midi in activeWrittenMidis
                val info = pitchInfoByWritten[midi]
                val isRoot = isActive && isPureMode && info?.intervalName == "Root"
                val isCNote = midi % 12 == 0
                val keyShape = RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 2.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 4.dp
                )
                val keyFill = when {
                    isRoot -> PitchEmerald
                    isActive -> IvoryKeyPressed
                    else -> IvoryKey
                }
                val keyBorder = when {
                    isRoot -> CrispOffWhite
                    isActive -> ElectricSky
                    else -> HardwareBorder.copy(alpha = 0.65f)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(keyShape)
                        .background(keyFill)
                        .border(if (isActive) 1.5.dp else 0.6.dp, keyBorder, keyShape),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (isActive) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isCompactHeight) 5.dp else 7.dp)
                                    .clip(CircleShape)
                                    .background(if (isRoot) StudioCharcoal else ElectricSky)
                            )
                            Text(
                                text = PitchTheory.pitchClassName(midi),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (isCompactHeight) 7.sp else 9.sp,
                                color = StudioCharcoal,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else if (isCNote) {
                        Text(
                            text = PitchTheory.noteNameWithOctave(midi),
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = if (isCompactHeight) 6.5.sp else 8.sp,
                            color = MutedSlateText,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
            }
        }

        // Black keys overlay (15 keys across 3 octaves, strictly non-interactive)
        for (oct in 0 until 3) {
            blackSpecsPerOctave.forEach { (semi, boundaryIdx) ->
                val midi = startMidiC + oct * 12 + semi
                val isActive = midi in activeWrittenMidis
                val info = pitchInfoByWritten[midi]
                val isRoot = isActive && isPureMode && info?.intervalName == "Root"
                val xOffset = (whiteKeyPitch * (oct * 7 + boundaryIdx)) - (whiteKeyGap / 2f) - (blackKeyWidth / 2f)
                val blackShape = RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 2.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 4.dp
                )
                val blackFill = when {
                    isRoot -> PitchEmerald
                    isActive -> ElectricSky
                    else -> EbonyKey
                }
                val blackBorder = when {
                    isRoot -> CrispOffWhite
                    isActive -> IvoryKeyPressed
                    else -> StudioCharcoal
                }

                Box(
                    modifier = Modifier
                        .offset(x = xOffset)
                        .width(blackKeyWidth)
                        .height(blackKeyHeight)
                        .clip(blackShape)
                        .background(blackFill)
                        .border(if (isActive) 1.2.dp else 0.7.dp, blackBorder, blackShape),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .size(if (isCompactHeight) 5.dp else 6.dp)
                                .clip(CircleShape)
                                .background(StudioCharcoal)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Green Zone (bottom-right): Harmony & Tuning Telemetry (Chord, Instrument Voice, Tone Vol,
 * Pure vs Equal + Root, A4 Hz, Transposition Key, Hold/Pedal badges, Held Note cents/Hz).
 */
@Composable
private fun HarmonyGreenBox(
    harmonyState: HarmonyUiState,
    sustainPedalDown: Boolean,
    isCompactHeight: Boolean,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier
) {
    val isPure = harmonyState.temperament == TemperamentMode.PURE
    val rootName = PitchTheory.pitchClassName(harmonyState.effectiveRootIndex)
    val tempLabel = if (isPure) {
        val autoTag = if (harmonyState.autoDetectRoot) "AUTO" else "LOCK"
        "PURE · $rootName ($autoTag)"
    } else {
        "EQUAL"
    }
    val hasActiveNotes = harmonyState.activeWrittenMidis.isNotEmpty()
    val tonePercent = (harmonyState.toneGain.coerceIn(0f, 1f) * 100f).roundToInt()

    Surface(
        modifier = modifier.testTag("monitor_harmony_green_box"),
        color = LcdMidnight,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, HarmonyGreenBorder.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 5.dp else 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Row: HARMONY label + Active Instrument Voice + Tone Vol %
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "HARMONY",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (isCompactHeight) 8.5.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = HarmonyGreenBorder,
                        maxLines = 1
                    )
                    Surface(
                        color = ElectricSkyContainer,
                        shape = RoundedCornerShape(3.dp),
                        border = BorderStroke(0.8.dp, ElectricSky.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = harmonyState.voice.label.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = if (isCompactHeight) 8.5.sp else 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricSky,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }
                }

                Text(
                    text = "TONE $tonePercent%",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCompactHeight) 8.5.sp else 10.sp,
                    color = ElectricSky,
                    maxLines = 1
                )
            }

            // Middle Hero: Giant Active Chord (Left) + Pure/Equal Temperament Pill (Right)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val chordLen = harmonyState.chordLabel.length
                val chordSize = when {
                    chordLen <= 4 -> if (isCompactHeight) 28.sp else if (isWideScreen) 42.sp else 34.sp
                    chordLen <= 7 -> if (isCompactHeight) 22.sp else if (isWideScreen) 34.sp else 28.sp
                    else -> if (isCompactHeight) 16.sp else 24.sp
                }
                Text(
                    text = harmonyState.chordLabel,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = chordSize,
                        lineHeight = (chordSize.value * 1.06f).sp
                    ),
                    color = if (hasActiveNotes) ElectricSky else MutedSlateText.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("monitor_chord_label")
                )

                Spacer(modifier = Modifier.width(6.dp))

                val tempAccent = if (isPure) PitchEmerald else WarmAmber
                Surface(
                    color = if (isPure) PitchEmerald.copy(alpha = 0.16f) else WarmAmberContainer,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, tempAccent.copy(alpha = 0.85f))
                ) {
                    Text(
                        text = tempLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = if (isCompactHeight) 8.5.sp else 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = tempAccent,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            // Tuning & Hold Flags Row: A4 Hz · Transposition · HOLD · PEDAL
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HarmonyTinyBadge(
                    text = String.format(Locale.US, "A4 %.1f", harmonyState.referencePitchHz),
                    color = WarmAmber,
                    highlighted = false,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier.weight(1.45f)
                )
                HarmonyTinyBadge(
                    text = "KEY ${harmonyState.transposition.shortLabel}",
                    color = if (harmonyState.transposition.semitoneOffset != 0) WarmAmber else CrispOffWhite,
                    highlighted = harmonyState.transposition.semitoneOffset != 0,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier.weight(1.05f)
                )
                HarmonyTinyBadge(
                    text = "HOLD",
                    color = if (harmonyState.chordHoldEnabled) ElectricSky else MutedSlateText,
                    highlighted = harmonyState.chordHoldEnabled,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier.weight(0.85f)
                )
                HarmonyTinyBadge(
                    text = "PED",
                    color = if (sustainPedalDown) PitchEmerald else MutedSlateText,
                    highlighted = sustainPedalDown,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier.weight(0.75f)
                )
            }

            Spacer(modifier = Modifier.height(if (isCompactHeight) 3.dp else 5.dp))

            // Bottom Strip: Live Held Notes Tuning Readout (cents vs ET in Pure, Hz in Equal)
            HeldNotesTuningStrip(
                notes = harmonyState.activePitchInfos,
                isPure = isPure,
                isCompactHeight = isCompactHeight
            )
        }
    }
}

@Composable
private fun HarmonyTinyBadge(
    text: String,
    color: Color,
    highlighted: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (highlighted) color.copy(alpha = 0.18f) else ConsoleSurface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(
            0.8.dp,
            if (highlighted) color.copy(alpha = 0.8f) else HardwareBorder.copy(alpha = 0.7f)
        )
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = if (isCompactHeight) 8.sp else 9.5.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = if (highlighted) color else color.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun HeldNotesTuningStrip(
    notes: List<NotePitchInfo>,
    isPure: Boolean,
    isCompactHeight: Boolean
) {
    val rowHeight = if (isCompactHeight) 22.dp else 26.dp

    if (notes.isEmpty()) {
        Surface(
            color = StudioCharcoal.copy(alpha = 0.65f),
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(0.8.dp, LcdBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "NO KEYS HELD · PLAY MIDI KEYBOARD",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (isCompactHeight) 8.sp else 9.5.sp,
                    color = MutedSlateText.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        return
    }

    val maxVisible = 4
    val hasOverflow = notes.size > maxVisible
    val visible = if (hasOverflow) notes.take(maxVisible - 1) else notes
    val hiddenCount = notes.size - visible.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        visible.forEach { info ->
            val isRoot = isPure && info.intervalName == "Root"
            val noteLabel = if (info.writtenName == info.soundingName) {
                info.soundingName
            } else {
                "${info.writtenName}→${info.soundingName}"
            }
            val tuningText = if (isPure) {
                val sign = if (info.centsOffsetFromEt >= 0.0) "+" else ""
                String.format(Locale.US, "%s%.1f¢", sign, info.centsOffsetFromEt)
            } else {
                String.format(Locale.US, "%.0fHz", info.frequencyHz)
            }
            val tuningColor = when {
                !isPure -> MutedSlateText
                abs(info.centsOffsetFromEt) < 0.05 -> PitchEmerald
                info.centsOffsetFromEt < 0.0 -> ElectricSky
                else -> WarmAmber
            }

            Surface(
                color = ConsoleSurface,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(
                    0.9.dp,
                    if (isRoot) PitchEmerald else ElectricSky.copy(alpha = 0.55f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = noteLabel,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompactHeight) 8.5.sp else 10.sp,
                        color = if (isRoot) PitchEmerald else CrispOffWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = tuningText,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (isCompactHeight) 7.5.sp else 9.sp,
                        color = tuningColor,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }

        if (hasOverflow) {
            Surface(
                color = ConsoleSurface,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(0.8.dp, HardwareBorder),
                modifier = Modifier.fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+$hiddenCount",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompactHeight) 8.5.sp else 10.sp,
                        color = MutedSlateText,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorOutputModeBadge(
    audioOutputMode: AudioOutputMode,
    modifier: Modifier = Modifier
) {
    val isSplit = audioOutputMode == AudioOutputMode.SPLIT_LR
    val accent = if (isSplit) WarmAmber else ElectricSky
    Surface(
        modifier = modifier,
        color = if (isSplit) WarmAmberContainer else ConsoleSurfaceBright,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = if (isSplit) "L:TONE · R:CLK" else "OUT: MIX",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
        }
    }
}
