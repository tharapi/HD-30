package com.example

import com.example.audio.HarmonyAudioEngine
import com.example.model.MetronomeSound
import com.example.model.SubdivisionMix
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class HarmonyAudioEngineTest {

    private fun renderSamples(engine: HarmonyAudioEngine, frames: Int): ShortArray {
        val frameSize = HarmonyAudioEngine.FRAME_SAMPLES
        val total = ShortArray(frames * frameSize)
        val buf = ShortArray(frameSize)
        for (f in 0 until frames) {
            engine.renderFrame(buf)
            buf.copyInto(total, destinationOffset = f * frameSize)
        }
        return total
    }

    private fun dftAmplitude(samples: ShortArray, start: Int, length: Int, freqHz: Double): Double {
        val omega = 2.0 * PI * freqHz / HarmonyAudioEngine.SAMPLE_RATE
        var re = 0.0
        var im = 0.0
        for (k in 0 until length) {
            val s = samples[start + k].toDouble()
            val angle = omega * (start + k)
            re += s * cos(angle)
            im += s * sin(angle)
        }
        return 2.0 * hypot(re, im) / length
    }

    @Test
    fun sustainedToneMixesLinearlyWithMetronomeWithoutCrossModulation() {
        val sounds = listOf(MetronomeSound.WOODBLOCK, MetronomeSound.CLICK, MetronomeSound.BELL)
        val voices = listOf(ToneVoice.PURE_SINE, ToneVoice.ORGAN)
        val denseMix = SubdivisionMix(
            accent = 1.0f,
            quarter = 0.9f,
            eighth = 0.8f,
            triplet = 0.7f,
            sixteenth = 0.8f
        )

        for (voice in voices) {
            for (sound in sounds) {
                val toneOnly = HarmonyAudioEngine().apply {
                    setToneVoice(voice)
                    setToneGain(1.0f)
                    updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
                }
                val metroOnly = HarmonyAudioEngine().apply {
                    setMetronomeGain(1.0f)
                    setMetronomeConfig(140, TimeSignature.FOUR_FOUR, sound, denseMix, false)
                    setMetronomePlaying(true)
                }
                val both = HarmonyAudioEngine().apply {
                    setToneVoice(voice)
                    setToneGain(1.0f)
                    setMetronomeGain(1.0f)
                    updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
                    setMetronomeConfig(140, TimeSignature.FOUR_FOUR, sound, denseMix, false)
                    setMetronomePlaying(true)
                }

                // Render ~0.7 seconds (60 frames = 30,720 samples covering multiple beats & subdivisions)
                val pcmTone = renderSamples(toneOnly, 60)
                val pcmMetro = renderSamples(metroOnly, 60)
                val pcmBoth = renderSamples(both, 60)

                var maxDiff = 0
                for (i in pcmBoth.indices) {
                    val expected = pcmTone[i].toInt() + pcmMetro[i].toInt()
                    val diff = abs(pcmBoth[i].toInt() - expected)
                    if (diff > maxDiff) maxDiff = diff
                }

                // Isolated dual-bus summing guarantees strictly linear superposition within 1-2 LSB rounding
                assertTrue(
                    "Expected linear superposition (<=2 LSB) for $voice + $sound, got $maxDiff LSB",
                    maxDiff <= 2
                )
            }
        }
    }

    @Test
    fun pureSineReferenceToneRemainsSteadyAndUndistortedAcrossBeats() {
        val freqHz = 440.0
        val mix = SubdivisionMix(accent = 1.0f, quarter = 0.85f, eighth = 0.6f, triplet = 0.0f, sixteenth = 0.0f)

        val metroOnly = HarmonyAudioEngine().apply {
            setMetronomeGain(1.0f)
            setMetronomeConfig(120, TimeSignature.FOUR_FOUR, MetronomeSound.WOODBLOCK, mix, false)
            setMetronomePlaying(true)
        }
        val both = HarmonyAudioEngine().apply {
            setToneVoice(ToneVoice.PURE_SINE)
            setToneGain(0.80f)
            setMetronomeGain(1.0f)
            updateActiveNotes(mapOf(69 to freqHz))
            setMetronomeConfig(120, TimeSignature.FOUR_FOUR, MetronomeSound.WOODBLOCK, mix, false)
            setMetronomePlaying(true)
        }

        val pcmMetro = renderSamples(metroOnly, 80)
        val pcmBoth = renderSamples(both, 80)

        // Isolate the tone component from the combined output
        val isolatedTone = ShortArray(pcmBoth.size) { i ->
            (pcmBoth[i].toInt() - pcmMetro[i].toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        // Skip initial attack (~15ms = 660 samples) and measure fundamental amplitude across beats.
        // 2205 samples at 44,100 Hz = exactly 22.0 cycles of 440 Hz (zero spectral leakage).
        val win = 2205
        val amplitudes = mutableListOf<Double>()
        var offset = 2000
        while (offset + win < isolatedTone.size) {
            amplitudes.add(dftAmplitude(isolatedTone, offset, win, freqHz))
            offset += 500
        }

        val minAmp = amplitudes.min()
        val maxAmp = amplitudes.max()
        val rippleDb = 20.0 * log10(maxAmp / minAmp)
        assertTrue("Expected sustained tone ripple < 0.05 dB, got $rippleDb dB", rippleDb < 0.05)

        // Pure Sine below bus knee should have negligible 3rd harmonic distortion (<-70 dB).
        // 4410 samples = exactly 44.0 cycles of 440 Hz and 132.0 cycles of 1320 Hz.
        val h1 = dftAmplitude(isolatedTone, 4000, 4410, freqHz)
        val h3 = dftAmplitude(isolatedTone, 4000, 4410, freqHz * 3.0)
        val h3Db = 20.0 * log10((h3 + 1e-6) / h1)
        assertTrue("Expected 3rd harmonic < -70 dB for PURE_SINE, got $h3Db dB", h3Db < -70.0)
    }

    @Test
    fun denseChordAndAllSubdivisionsStayWithinSafeHeadroom() {
        val engine = HarmonyAudioEngine().apply {
            setToneVoice(ToneVoice.ORGAN)
            setToneGain(1.0f)
            setMetronomeGain(1.0f)
            updateActiveNotes(
                mapOf(
                    48 to 130.8128,
                    55 to 195.9977,
                    60 to 261.6256,
                    64 to 329.6276,
                    67 to 391.9954,
                    72 to 523.2511
                )
            )
            setMetronomeConfig(
                bpm = 200,
                timeSignature = TimeSignature.FOUR_FOUR,
                sound = MetronomeSound.BELL,
                mix = SubdivisionMix(1f, 1f, 1f, 1f, 1f),
                alternateMuteBar = false
            )
            setMetronomePlaying(true)
        }

        val pcm = renderSamples(engine, 80)
        val peak = pcm.maxOf { abs(it.toInt()) }
        // Max ceiling is (0.48 + 0.38) * 32767 = 28180 -> guaranteed digital headroom without clipping
        assertTrue("Expected peak <= 28500 (safe headroom below 0 dBFS), got $peak", peak <= 28500)
    }

    @Test
    fun releasingChordDoesNotSpikeAmplitude() {
        val engine = HarmonyAudioEngine().apply {
            setToneVoice(ToneVoice.PURE_SINE)
            setToneGain(0.80f)
            updateActiveNotes(mapOf(69 to 440.0, 81 to 880.0, 88 to 1320.0))
        }

        // Warm up steady chord
        val steady = renderSamples(engine, 20)
        val steadyPeak = steady.copyOfRange(steady.size - 4096, steady.size).maxOf { abs(it.toInt()) }

        // Release all notes
        engine.updateActiveNotes(emptyMap())
        val release = renderSamples(engine, 20)
        val releasePeak = release.maxOf { abs(it.toInt()) }

        assertTrue(
            "Expected release peak ($releasePeak) not to jump above steady chord peak ($steadyPeak)",
            releasePeak <= steadyPeak + 10
        )
    }
}
