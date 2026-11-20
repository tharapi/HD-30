import SwiftUI

struct ConsoleLcdHeaderView: View {
    @ObservedObject var viewModel: ConsoleViewModel
    let onOpenMidi: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            // Top bar: Brand + Output Mode + MIDI + Panic
            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(viewModel.metronome.isPlaying ? HDTheme.pitchEmerald : HDTheme.electricSky)
                        .frame(width: 8, height: 8)
                    Text("HD-300")
                        .font(.system(.headline, design: .monospaced).weight(.bold))
                        .foregroundColor(HDTheme.crispOffWhite)
                    Text("HARMONY DIRECTOR")
                        .font(.system(.caption2, design: .monospaced).weight(.semibold))
                        .foregroundColor(HDTheme.mutedSlateText)
                        .lineLimit(1)
                }

                Spacer(minLength: 4)

                // Audio Output Split L/R Toggle
                Button {
                    viewModel.toggleAudioOutputMode()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: viewModel.audioOutputMode == .splitLR ? "arrow.left.and.right.circle.fill" : "speaker.wave.2.fill")
                            .font(.caption)
                        Text(viewModel.audioOutputMode.shortLabel)
                            .font(.system(.caption2, design: .monospaced).weight(.bold))
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(
                        viewModel.audioOutputMode == .splitLR
                            ? HDTheme.warmAmberContainer
                            : HDTheme.consoleSurface
                    )
                    .foregroundColor(
                        viewModel.audioOutputMode == .splitLR
                            ? HDTheme.warmAmber
                            : HDTheme.crispOffWhite
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(
                                viewModel.audioOutputMode == .splitLR ? HDTheme.warmAmber : HDTheme.hardwareBorder,
                                lineWidth: 1
                            )
                    )
                    .cornerRadius(8)
                }

                // MIDI Status Button
                Button(action: onOpenMidi) {
                    HStack(spacing: 4) {
                        Image(systemName: "pianokeys")
                            .font(.caption)
                        Text(viewModel.midiState.connectedDeviceName != nil ? "MIDI ON" : "MIDI")
                            .font(.system(.caption2, design: .monospaced).weight(.bold))
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(
                        viewModel.midiState.connectedDeviceName != nil
                            ? HDTheme.electricSkyContainer
                            : HDTheme.consoleSurface
                    )
                    .foregroundColor(
                        viewModel.midiState.connectedDeviceName != nil
                            ? HDTheme.electricSky
                            : HDTheme.mutedSlateText
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(
                                viewModel.midiState.connectedDeviceName != nil ? HDTheme.electricSky : HDTheme.hardwareBorder,
                                lineWidth: 1
                            )
                    )
                    .cornerRadius(8)
                }

                // Panic / All Sound Off
                Button {
                    viewModel.stopAllSound()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.title3)
                        .foregroundColor(HDTheme.downbeatCrimson)
                }
                .accessibilityLabel("All Sound Off")
            }

            // Backlit LCD Display Window
            VStack(spacing: 8) {
                HStack(alignment: .center, spacing: 12) {
                    // Left: Chord / Pitch readout
                    VStack(alignment: .leading, spacing: 2) {
                        Text("HARMONY · \(viewModel.harmony.voice.label.uppercased())")
                            .font(.system(size: 10, weight: .semibold, design: .monospaced))
                            .foregroundColor(HDTheme.mutedSlateText)
                        Text(viewModel.harmony.chordLabel)
                            .font(.system(size: 26, weight: .bold, design: .monospaced))
                            .foregroundColor(HDTheme.electricSky)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        HStack(spacing: 6) {
                            Text(viewModel.harmony.temperament == .pure
                                 ? "PURE (\(PitchTheory.pitchClassName(viewModel.harmony.effectiveRootIndex))\(viewModel.harmony.autoDetectRoot ? " Auto" : ""))"
                                 : "EQUAL")
                                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                                .foregroundColor(viewModel.harmony.temperament == .pure ? HDTheme.pitchEmerald : HDTheme.mutedSlateText)
                            Text("·")
                                .foregroundColor(HDTheme.mutedSlateText)
                            Text(String(format: "A4=%.1fHz", viewModel.harmony.referencePitchHz))
                                .font(.system(size: 11, weight: .medium, design: .monospaced))
                                .foregroundColor(HDTheme.crispOffWhite)
                            Text("· \(viewModel.harmony.transposition.shortLabel)")
                                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                                .foregroundColor(HDTheme.warmAmber)
                        }
                    }

                    Spacer(minLength: 4)

                    // Right: Tempo + Meter readout
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(viewModel.beatSnapshot.isMutedBar ? "MUTED BAR" : "TEMPO · \(viewModel.metronome.timeSignature.label)")
                            .font(.system(size: 10, weight: .semibold, design: .monospaced))
                            .foregroundColor(viewModel.beatSnapshot.isMutedBar ? HDTheme.warmAmber : HDTheme.mutedSlateText)
                        HStack(alignment: .firstTextBaseline, spacing: 3) {
                            Text("\(viewModel.metronome.bpm)")
                                .font(.system(size: 26, weight: .bold, design: .monospaced))
                                .foregroundColor(viewModel.metronome.isPlaying ? HDTheme.warmAmber : HDTheme.crispOffWhite)
                            Text("BPM")
                                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                                .foregroundColor(HDTheme.mutedSlateText)
                        }
                        Text(viewModel.metronome.metronomeSound.label)
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundColor(HDTheme.mutedSlateText)
                    }
                }

                // Beat Indicator LED Strip
                let totalBeats = max(viewModel.metronome.timeSignature.beatsPerMeasure, 1)
                HStack(spacing: 4) {
                    ForEach(0..<totalBeats, id: \.self) { beatIdx in
                        let isActive = viewModel.beatSnapshot.isPlaying && viewModel.beatSnapshot.beatIndex == beatIdx
                        let isDownbeat = beatIdx == 0
                        let activeColor: Color = {
                            if viewModel.beatSnapshot.isMutedBar { return HDTheme.mutedSlateText.opacity(0.6) }
                            return isDownbeat ? HDTheme.downbeatCrimson : HDTheme.warmAmber
                        }()
                        RoundedRectangle(cornerRadius: 3)
                            .fill(isActive ? activeColor : HDTheme.consoleSurface)
                            .frame(height: 6)
                    }
                }
            }
            .padding(12)
            .background(HDTheme.lcdMidnight)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(HDTheme.lcdBorder, lineWidth: 1.5)
            )
            .cornerRadius(12)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(HDTheme.anodizedSlate)
    }
}
