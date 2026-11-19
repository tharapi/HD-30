package com.example.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class DecodedWavSample(
    val pcm: FloatArray,
    val sampleRate: Int
)

class InstrumentSampleZone(
    val pcm: ShortArray,
    val sampleRate: Int,
    val midiNote: Int,
    val rootFreqHz: Double,
    val loopStart: Int,
    val loopEnd: Int
) {
    val hasLoop: Boolean get() = loopEnd > loopStart && loopEnd <= pcm.size
}

/**
 * Lightweight RIFF/WAVE PCM decoder (16-bit and 24-bit, mono or multi-channel downmixed to mono)
 * with RIFF 'smpl' loop-point and pitch metadata parsing.
 * Used for loading bundled CC0 percussion and orchestral samples (VSCO 2 Community Edition) into
 * RAM for zero-allocation, sample-accurate playback inside [HarmonyAudioEngine].
 */
object WavSampleDecoder {
    private const val AUDIO_FORMAT_PCM = 1

    fun decode(input: InputStream): DecodedWavSample? {
        val bytes = input.readBytes()
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): DecodedWavSample? {
        val parsed = parseChunks(bytes) ?: return null
        val frameCount = parsed.frameCount
        val mono = FloatArray(frameCount)
        val invChannels = 1.0f / parsed.numChannels.toFloat()

        if (parsed.bitsPerSample == 16) {
            var offset = parsed.dataOffset
            for (i in 0 until frameCount) {
                var sum = 0f
                for (c in 0 until parsed.numChannels) {
                    val sample = parsed.buf.getShort(offset).toInt()
                    sum += sample / 32768.0f
                    offset += 2
                }
                mono[i] = (sum * invChannels).coerceIn(-1.0f, 1.0f)
            }
        } else {
            var offset = parsed.dataOffset
            for (i in 0 until frameCount) {
                var sum = 0f
                for (c in 0 until parsed.numChannels) {
                    val b0 = bytes[offset].toInt() and 0xFF
                    val b1 = bytes[offset + 1].toInt() and 0xFF
                    val b2 = bytes[offset + 2].toInt() and 0xFF
                    var value = b0 or (b1 shl 8) or (b2 shl 16)
                    if ((value and 0x800000) != 0) {
                        value = value or (0xFF shl 24)
                    }
                    sum += value / 8388608.0f
                    offset += 3
                }
                mono[i] = (sum * invChannels).coerceIn(-1.0f, 1.0f)
            }
        }

        return DecodedWavSample(pcm = mono, sampleRate = parsed.sampleRate)
    }

    fun decodeInstrumentZone(input: InputStream, fallbackMidi: Int = 60): InstrumentSampleZone? {
        val bytes = input.readBytes()
        return decodeInstrumentZone(bytes, fallbackMidi)
    }

    fun decodeInstrumentZone(bytes: ByteArray, fallbackMidi: Int = 60): InstrumentSampleZone? {
        val parsed = parseChunks(bytes) ?: return null
        val frameCount = parsed.frameCount
        val pcm = ShortArray(frameCount)

        if (parsed.bitsPerSample == 16 && parsed.numChannels == 1) {
            var offset = parsed.dataOffset
            for (i in 0 until frameCount) {
                pcm[i] = parsed.buf.getShort(offset)
                offset += 2
            }
        } else if (parsed.bitsPerSample == 16) {
            var offset = parsed.dataOffset
            val ch = parsed.numChannels
            for (i in 0 until frameCount) {
                var sum = 0
                for (c in 0 until ch) {
                    sum += parsed.buf.getShort(offset).toInt()
                    offset += 2
                }
                pcm[i] = (sum / ch).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        } else {
            var offset = parsed.dataOffset
            val ch = parsed.numChannels
            for (i in 0 until frameCount) {
                var sum = 0L
                for (c in 0 until ch) {
                    val b0 = bytes[offset].toInt() and 0xFF
                    val b1 = bytes[offset + 1].toInt() and 0xFF
                    val b2 = bytes[offset + 2].toInt() and 0xFF
                    var value = b0 or (b1 shl 8) or (b2 shl 16)
                    if ((value and 0x800000) != 0) {
                        value = value or (0xFF shl 24)
                    }
                    sum += (value shr 8).toLong()
                    offset += 3
                }
                pcm[i] = (sum / ch).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        val midiNote = if (parsed.smplMidiNote in 0..127) parsed.smplMidiNote else fallbackMidi
        val fracSemitones = (parsed.smplPitchFraction.toLong() and 0xFFFFFFFFL) / 4294967296.0
        val rootFreqHz = 440.0 * 2.0.pow(((midiNote - 69) + fracSemitones) / 12.0)

        val validLoop = parsed.loopStart >= 0 &&
            parsed.loopEnd > parsed.loopStart &&
            parsed.loopEnd <= frameCount
        val loopStart = if (validLoop) parsed.loopStart else 0
        val loopEnd = if (validLoop) parsed.loopEnd else 0

        return InstrumentSampleZone(
            pcm = pcm,
            sampleRate = parsed.sampleRate,
            midiNote = midiNote,
            rootFreqHz = rootFreqHz,
            loopStart = loopStart,
            loopEnd = loopEnd
        )
    }

    private class ParsedWavChunks(
        val buf: ByteBuffer,
        val numChannels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val frameCount: Int,
        val smplMidiNote: Int,
        val smplPitchFraction: Int,
        val loopStart: Int,
        val loopEnd: Int
    )

    private fun parseChunks(bytes: ByteArray): ParsedWavChunks? {
        if (bytes.size < 44) return null
        if (!matchesTag(bytes, 0, "RIFF") || !matchesTag(bytes, 8, "WAVE")) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var audioFormat = -1
        var numChannels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0
        var smplMidiNote = -1
        var smplPitchFraction = 0
        var loopStart = 0
        var loopEnd = 0

        while (pos + 8 <= bytes.size) {
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize < 0) return null
            val bodyStart = pos + 8
            val bodyEnd = bodyStart + chunkSize
            if (bodyEnd > bytes.size) break

            when {
                matchesTag(bytes, pos, "fmt ") && chunkSize >= 16 -> {
                    audioFormat = buf.getShort(bodyStart).toInt() and 0xFFFF
                    numChannels = buf.getShort(bodyStart + 2).toInt() and 0xFFFF
                    sampleRate = buf.getInt(bodyStart + 4)
                    bitsPerSample = buf.getShort(bodyStart + 14).toInt() and 0xFFFF
                }
                matchesTag(bytes, pos, "smpl") && chunkSize >= 36 -> {
                    smplMidiNote = buf.getInt(bodyStart + 12)
                    smplPitchFraction = buf.getInt(bodyStart + 16)
                    val numLoops = buf.getInt(bodyStart + 28)
                    if (numLoops > 0 && chunkSize >= 60) {
                        loopStart = buf.getInt(bodyStart + 44)
                        loopEnd = buf.getInt(bodyStart + 48)
                    }
                }
                matchesTag(bytes, pos, "data") -> {
                    dataOffset = bodyStart
                    dataSize = chunkSize
                }
            }

            // RIFF chunks are padded to even byte boundaries
            pos = bodyEnd + (chunkSize and 1)
        }

        if (audioFormat != AUDIO_FORMAT_PCM || numChannels <= 0 || sampleRate <= 0 || dataOffset < 0) {
            return null
        }
        if (bitsPerSample != 16 && bitsPerSample != 24) return null
        val frameBytes = (bitsPerSample / 8) * numChannels
        if (frameBytes <= 0 || dataSize < frameBytes) return null

        return ParsedWavChunks(
            buf = buf,
            numChannels = numChannels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            frameCount = dataSize / frameBytes,
            smplMidiNote = smplMidiNote,
            smplPitchFraction = smplPitchFraction,
            loopStart = loopStart,
            loopEnd = loopEnd
        )
    }

    private fun matchesTag(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + 4 > bytes.size) return false
        return bytes[offset].toInt().toChar() == tag[0] &&
            bytes[offset + 1].toInt().toChar() == tag[1] &&
            bytes[offset + 2].toInt().toChar() == tag[2] &&
            bytes[offset + 3].toInt().toChar() == tag[3]
    }
}
