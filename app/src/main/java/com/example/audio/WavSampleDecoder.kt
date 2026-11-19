package com.example.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecodedWavSample(
    val pcm: FloatArray,
    val sampleRate: Int
)

/**
 * Lightweight RIFF/WAVE PCM decoder (16-bit and 24-bit, mono or multi-channel downmixed to mono).
 * Used for loading bundled CC0 percussion samples (VSCO 2 Community Edition) into RAM at startup
 * for zero-allocation, sample-accurate playback inside [HarmonyAudioEngine].
 */
object WavSampleDecoder {
    private const val AUDIO_FORMAT_PCM = 1

    fun decode(input: InputStream): DecodedWavSample? {
        val bytes = input.readBytes()
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): DecodedWavSample? {
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

        val bytesPerSample = bitsPerSample / 8
        if (bitsPerSample != 16 && bitsPerSample != 24) return null
        val frameBytes = bytesPerSample * numChannels
        if (frameBytes <= 0 || dataSize < frameBytes) return null

        val frameCount = dataSize / frameBytes
        val mono = FloatArray(frameCount)
        val invChannels = 1.0f / numChannels.toFloat()

        if (bitsPerSample == 16) {
            var offset = dataOffset
            for (i in 0 until frameCount) {
                var sum = 0f
                for (c in 0 until numChannels) {
                    val sample = buf.getShort(offset).toInt()
                    sum += sample / 32768.0f
                    offset += 2
                }
                mono[i] = (sum * invChannels).coerceIn(-1.0f, 1.0f)
            }
        } else {
            var offset = dataOffset
            for (i in 0 until frameCount) {
                var sum = 0f
                for (c in 0 until numChannels) {
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

        return DecodedWavSample(pcm = mono, sampleRate = sampleRate)
    }

    private fun matchesTag(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + 4 > bytes.size) return false
        return bytes[offset].toInt().toChar() == tag[0] &&
            bytes[offset + 1].toInt().toChar() == tag[1] &&
            bytes[offset + 2].toInt().toChar() == tag[2] &&
            bytes[offset + 3].toInt().toChar() == tag[3]
    }
}
