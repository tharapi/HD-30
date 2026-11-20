import Foundation

struct DecodedWavSample {
    let pcm: [Float]
    let sampleRate: Int
}

struct InstrumentSampleZone {
    let pcm: [Int16]
    let sampleRate: Int
    let midiNote: Int
    let rootFreqHz: Double
    let loopStart: Int
    let loopEnd: Int

    var hasLoop: Bool {
        loopEnd > loopStart && loopEnd <= pcm.count
    }
}

/// Lightweight RIFF/WAVE PCM decoder (16-bit and 24-bit, mono or multi-channel downmixed to mono)
/// with RIFF 'smpl' loop-point and pitch metadata parsing.
enum WavSampleDecoder {
    private static let audioFormatPCM = 1

    private struct ParsedWavChunks {
        let bytes: [UInt8]
        let numChannels: Int
        let sampleRate: Int
        let bitsPerSample: Int
        let dataOffset: Int
        let frameCount: Int
        let smplMidiNote: Int
        let smplPitchFraction: UInt32
        let loopStart: Int
        let loopEnd: Int
    }

    static func decode(_ data: Data) -> DecodedWavSample? {
        guard let parsed = parseChunks([UInt8](data)) else { return nil }
        let frameCount = parsed.frameCount
        var mono = [Float](repeating: 0, count: frameCount)
        let invChannels = 1.0 / Float(parsed.numChannels)
        let bytes = parsed.bytes

        if parsed.bitsPerSample == 16 {
            var offset = parsed.dataOffset
            for i in 0..<frameCount {
                var sum: Float = 0
                for _ in 0..<parsed.numChannels {
                    let sample = readInt16LE(bytes, offset)
                    sum += Float(sample) / 32768.0
                    offset += 2
                }
                mono[i] = min(max(sum * invChannels, -1.0), 1.0)
            }
        } else {
            var offset = parsed.dataOffset
            for i in 0..<frameCount {
                var sum: Float = 0
                for _ in 0..<parsed.numChannels {
                    let b0 = Int32(bytes[offset])
                    let b1 = Int32(bytes[offset + 1])
                    let b2 = Int32(bytes[offset + 2])
                    var value = b0 | (b1 << 8) | (b2 << 16)
                    if (value & 0x800000) != 0 {
                        value |= Int32(bitPattern: 0xFF000000)
                    }
                    sum += Float(value) / 8388608.0
                    offset += 3
                }
                mono[i] = min(max(sum * invChannels, -1.0), 1.0)
            }
        }

        return DecodedWavSample(pcm: mono, sampleRate: parsed.sampleRate)
    }

    static func decodeInstrumentZone(_ data: Data, fallbackMidi: Int = 60) -> InstrumentSampleZone? {
        guard let parsed = parseChunks([UInt8](data)) else { return nil }
        let frameCount = parsed.frameCount
        var pcm = [Int16](repeating: 0, count: frameCount)
        let bytes = parsed.bytes

        if parsed.bitsPerSample == 16 && parsed.numChannels == 1 {
            var offset = parsed.dataOffset
            for i in 0..<frameCount {
                pcm[i] = readInt16LE(bytes, offset)
                offset += 2
            }
        } else if parsed.bitsPerSample == 16 {
            var offset = parsed.dataOffset
            let ch = parsed.numChannels
            for i in 0..<frameCount {
                var sum = 0
                for _ in 0..<ch {
                    sum += Int(readInt16LE(bytes, offset))
                    offset += 2
                }
                pcm[i] = Int16(clamping: sum / ch)
            }
        } else {
            var offset = parsed.dataOffset
            let ch = parsed.numChannels
            for i in 0..<frameCount {
                var sum: Int64 = 0
                for _ in 0..<ch {
                    let b0 = Int32(bytes[offset])
                    let b1 = Int32(bytes[offset + 1])
                    let b2 = Int32(bytes[offset + 2])
                    var value = b0 | (b1 << 8) | (b2 << 16)
                    if (value & 0x800000) != 0 {
                        value |= Int32(bitPattern: 0xFF000000)
                    }
                    sum += Int64(value >> 8)
                    offset += 3
                }
                pcm[i] = Int16(clamping: Int(sum / Int64(ch)))
            }
        }

        let midiNote = (0...127).contains(parsed.smplMidiNote) ? parsed.smplMidiNote : fallbackMidi
        let fracSemitones = Double(parsed.smplPitchFraction) / 4294967296.0
        let rootFreqHz = 440.0 * pow(2.0, (Double(midiNote - 69) + fracSemitones) / 12.0)

        let validLoop = parsed.loopStart >= 0 &&
            parsed.loopEnd > parsed.loopStart &&
            parsed.loopEnd <= frameCount
        let loopStart = validLoop ? parsed.loopStart : 0
        let loopEnd = validLoop ? parsed.loopEnd : 0

        return InstrumentSampleZone(
            pcm: pcm,
            sampleRate: parsed.sampleRate,
            midiNote: midiNote,
            rootFreqHz: rootFreqHz,
            loopStart: loopStart,
            loopEnd: loopEnd
        )
    }

    private static func parseChunks(_ bytes: [UInt8]) -> ParsedWavChunks? {
        guard bytes.count >= 44 else { return nil }
        guard matchesTag(bytes, 0, "RIFF"), matchesTag(bytes, 8, "WAVE") else { return nil }

        var pos = 12
        var audioFormat = -1
        var numChannels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0
        var smplMidiNote = -1
        var smplPitchFraction: UInt32 = 0
        var loopStart = 0
        var loopEnd = 0

        while pos + 8 <= bytes.count {
            let chunkSizeU32 = readUInt32LE(bytes, pos + 4)
            let chunkSize = Int(chunkSizeU32)
            guard chunkSize >= 0 else { return nil }
            let bodyStart = pos + 8
            let bodyEnd = bodyStart + chunkSize
            if bodyEnd > bytes.count { break }

            if matchesTag(bytes, pos, "fmt ") && chunkSize >= 16 {
                audioFormat = Int(readUInt16LE(bytes, bodyStart))
                numChannels = Int(readUInt16LE(bytes, bodyStart + 2))
                sampleRate = Int(readUInt32LE(bytes, bodyStart + 4))
                bitsPerSample = Int(readUInt16LE(bytes, bodyStart + 14))
            } else if matchesTag(bytes, pos, "smpl") && chunkSize >= 36 {
                smplMidiNote = Int(readInt32LE(bytes, bodyStart + 12))
                smplPitchFraction = readUInt32LE(bytes, bodyStart + 16)
                let numLoops = Int(readInt32LE(bytes, bodyStart + 28))
                if numLoops > 0 && chunkSize >= 60 {
                    loopStart = Int(readInt32LE(bytes, bodyStart + 44))
                    loopEnd = Int(readInt32LE(bytes, bodyStart + 48))
                }
            } else if matchesTag(bytes, pos, "data") {
                dataOffset = bodyStart
                dataSize = chunkSize
            }

            pos = bodyEnd + (chunkSize & 1)
        }

        guard audioFormat == audioFormatPCM,
              numChannels > 0,
              sampleRate > 0,
              dataOffset >= 0,
              (bitsPerSample == 16 || bitsPerSample == 24) else {
            return nil
        }

        let frameBytes = (bitsPerSample / 8) * numChannels
        guard frameBytes > 0, dataSize >= frameBytes else { return nil }

        return ParsedWavChunks(
            bytes: bytes,
            numChannels: numChannels,
            sampleRate: sampleRate,
            bitsPerSample: bitsPerSample,
            dataOffset: dataOffset,
            frameCount: dataSize / frameBytes,
            smplMidiNote: smplMidiNote,
            smplPitchFraction: smplPitchFraction,
            loopStart: loopStart,
            loopEnd: loopEnd
        )
    }

    private static func matchesTag(_ bytes: [UInt8], _ offset: Int, _ tag: String) -> Bool {
        guard offset + 4 <= bytes.count else { return false }
        let scalars = Array(tag.utf8)
        guard scalars.count == 4 else { return false }
        return bytes[offset] == scalars[0] &&
            bytes[offset + 1] == scalars[1] &&
            bytes[offset + 2] == scalars[2] &&
            bytes[offset + 3] == scalars[3]
    }

    private static func readUInt16LE(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
    }

    private static func readInt16LE(_ bytes: [UInt8], _ offset: Int) -> Int16 {
        Int16(bitPattern: readUInt16LE(bytes, offset))
    }

    private static func readUInt32LE(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) |
            (UInt32(bytes[offset + 1]) << 8) |
            (UInt32(bytes[offset + 2]) << 16) |
            (UInt32(bytes[offset + 3]) << 24)
    }

    private static func readInt32LE(_ bytes: [UInt8], _ offset: Int) -> Int32 {
        Int32(bitPattern: readUInt32LE(bytes, offset))
    }
}
