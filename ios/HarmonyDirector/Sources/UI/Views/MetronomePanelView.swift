import SwiftUI

struct MetronomePanelView: View {
    @ObservedObject var viewModel: ConsoleViewModel

    private static let quickMeters: [TimeSignature] = [
        TimeSignature(numerator: 2, denominator: 4),
        TimeSignature(numerator: 3, denominator: 4),
        TimeSignature(numerator: 4, denominator: 4),
        TimeSignature(numerator: 5, denominator: 4),
        TimeSignature(numerator: 6, denominator: 8),
        TimeSignature(numerator: 7, denominator: 8)
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // 1. Transport & Tempo Card
                consoleCard(title: "TRANSPORT & TEMPO") {
                    HStack(spacing: 10) {
                        Button {
                            viewModel.toggleMetronomePlaying()
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: viewModel.metronome.isPlaying ? "stop.fill" : "play.fill")
                                Text(viewModel.metronome.isPlaying ? "STOP" : "START")
                                    .font(.system(.subheadline, design: .monospaced).weight(.bold))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(viewModel.metronome.isPlaying ? HDTheme.downbeatCrimson : HDTheme.pitchEmerald)
                            .foregroundColor(HDTheme.studioCharcoal)
                            .cornerRadius(10)
                        }

                        Button {
                            viewModel.onTapTempo()
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "hand.tap.fill")
                                Text("TAP")
                                    .font(.system(.subheadline, design: .monospaced).weight(.bold))
                            }
                            .frame(width: 104)
                            .padding(.vertical, 12)
                            .background(HDTheme.warmAmberContainer)
                            .foregroundColor(HDTheme.warmAmber)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(HDTheme.warmAmber, lineWidth: 1)
                            )
                            .cornerRadius(10)
                        }
                    }

                    // BPM Adjust Row
                    HStack(spacing: 8) {
                        bpmButton("-5") { viewModel.adjustBpm(-5) }
                        bpmButton("-1") { viewModel.adjustBpm(-1) }
                        Spacer()
                        Text("\(viewModel.metronome.bpm) BPM")
                            .font(.system(.title3, design: .monospaced).weight(.bold))
                            .foregroundColor(HDTheme.warmAmber)
                        Spacer()
                        bpmButton("+1") { viewModel.adjustBpm(1) }
                        bpmButton("+5") { viewModel.adjustBpm(5) }
                    }

                    Slider(
                        value: Binding(
                            get: { Double(viewModel.metronome.bpm) },
                            set: { viewModel.setBpm(Int(round($0))) }
                        ),
                        in: 32...280,
                        step: 1
                    )
                    .tint(HDTheme.warmAmber)

                    // Metronome Master Volume
                    HStack(spacing: 10) {
                        Image(systemName: "metronome.fill")
                            .font(.caption)
                            .foregroundColor(HDTheme.warmAmber)
                        Text("Metro Vol")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(HDTheme.mutedSlateText)
                        Slider(
                            value: Binding(
                                get: { Double(viewModel.metronome.metronomeGain) },
                                set: { viewModel.setMetronomeGain(Float($0)) }
                            ),
                            in: 0...1
                        )
                        .tint(HDTheme.warmAmber)
                        Text("\(Int(round(viewModel.metronome.metronomeGain * 100)))%")
                            .font(.system(.caption, design: .monospaced).weight(.semibold))
                            .foregroundColor(HDTheme.crispOffWhite)
                            .frame(width: 38, alignment: .trailing)
                    }
                }

                // 2. Time Signature & Sound Card
                consoleCard(title: "METER & PERCUSSION VOICE") {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("BEATS (1-16)")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(HDTheme.mutedSlateText)
                            Picker("Numerator", selection: Binding(
                                get: { viewModel.metronome.timeSignature.numerator },
                                set: { viewModel.setTimeSignatureNumerator($0) }
                            )) {
                                ForEach(TimeSignature.minNumerator...TimeSignature.maxNumerator, id: \.self) { n in
                                    Text("\(n)").tag(n)
                                }
                            }
                            .pickerStyle(.menu)
                            .tint(HDTheme.electricSky)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(HDTheme.anodizedSlate)
                            .cornerRadius(8)
                        }

                        VStack(alignment: .leading, spacing: 4) {
                            Text("UNIT")
                                .font(.system(size: 10, weight: .bold, design: .monospaced))
                                .foregroundColor(HDTheme.mutedSlateText)
                            Picker("Denominator", selection: Binding(
                                get: { viewModel.metronome.timeSignature.denominator },
                                set: { viewModel.setTimeSignatureDenominator($0) }
                            )) {
                                ForEach(TimeSignature.allowedDenominators, id: \.self) { d in
                                    Text("/\(d)").tag(d)
                                }
                            }
                            .pickerStyle(.menu)
                            .tint(HDTheme.electricSky)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(HDTheme.anodizedSlate)
                            .cornerRadius(8)
                        }

                        Spacer()

                        Button {
                            viewModel.toggleAlternateMuteBar()
                        } label: {
                            VStack(spacing: 2) {
                                Text("ALT MUTE")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                Text(viewModel.metronome.alternateMuteBar ? "ON (1+1)" : "OFF")
                                    .font(.system(.caption2, design: .monospaced).weight(.bold))
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(viewModel.metronome.alternateMuteBar ? HDTheme.warmAmberContainer : HDTheme.anodizedSlate)
                            .foregroundColor(viewModel.metronome.alternateMuteBar ? HDTheme.warmAmber : HDTheme.mutedSlateText)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(viewModel.metronome.alternateMuteBar ? HDTheme.warmAmber : HDTheme.hardwareBorder, lineWidth: 1)
                            )
                            .cornerRadius(8)
                        }
                    }

                    // Quick Time Signatures
                    HStack(spacing: 6) {
                        ForEach(Self.quickMeters, id: \.label) { ts in
                            let selected = viewModel.metronome.timeSignature == ts
                            Button {
                                viewModel.selectTimeSignature(ts)
                            } label: {
                                Text(ts.label)
                                    .font(.system(.caption2, design: .monospaced).weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 6)
                                    .background(selected ? HDTheme.electricSkyContainer : HDTheme.anodizedSlate)
                                    .foregroundColor(selected ? HDTheme.electricSky : HDTheme.crispOffWhite)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 7)
                                            .stroke(selected ? HDTheme.electricSky : HDTheme.hardwareBorder, lineWidth: 1)
                                    )
                                    .cornerRadius(7)
                            }
                        }
                    }

                    // Metronome Sound Voices
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                        ForEach(MetronomeSound.allCases) { sound in
                            let selected = viewModel.metronome.metronomeSound == sound
                            Button {
                                viewModel.selectMetronomeSound(sound)
                            } label: {
                                Text(sound.label)
                                    .font(.system(.caption, design: .monospaced).weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(selected ? HDTheme.warmAmberContainer : HDTheme.anodizedSlate)
                                    .foregroundColor(selected ? HDTheme.warmAmber : HDTheme.crispOffWhite)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 8)
                                            .stroke(selected ? HDTheme.warmAmber : HDTheme.hardwareBorder, lineWidth: 1)
                                    )
                                    .cornerRadius(8)
                            }
                        }
                    }
                }

                // 3. 5-Channel Subdivision Mixer Card
                consoleCard(title: "5-CHANNEL SUBDIVISION MIXER") {
                    subdivisionSlider(
                        label: "Accent (Beat 1)",
                        value: viewModel.metronome.subdivisionMix.accent,
                        color: HDTheme.downbeatCrimson
                    ) { val in
                        viewModel.updateSubdivisionMix { $0.accent = val }
                    }
                    subdivisionSlider(
                        label: "Quarter (1/4)",
                        value: viewModel.metronome.subdivisionMix.quarter,
                        color: HDTheme.warmAmber
                    ) { val in
                        viewModel.updateSubdivisionMix { $0.quarter = val }
                    }
                    subdivisionSlider(
                        label: "Eighth (1/8)",
                        value: viewModel.metronome.subdivisionMix.eighth,
                        color: HDTheme.electricSky
                    ) { val in
                        viewModel.updateSubdivisionMix { $0.eighth = val }
                    }
                    subdivisionSlider(
                        label: "Triplet (3)",
                        value: viewModel.metronome.subdivisionMix.triplet,
                        color: HDTheme.pitchEmerald
                    ) { val in
                        viewModel.updateSubdivisionMix { $0.triplet = val }
                    }
                    subdivisionSlider(
                        label: "Sixteenth (1/16)",
                        value: viewModel.metronome.subdivisionMix.sixteenth,
                        color: HDTheme.electricSky
                    ) { val in
                        viewModel.updateSubdivisionMix { $0.sixteenth = val }
                    }
                }
            }
            .padding(14)
        }
        .background(HDTheme.studioCharcoal)
    }

    private func bpmButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(.caption, design: .monospaced).weight(.bold))
                .frame(width: 44, height: 34)
                .background(HDTheme.anodizedSlate)
                .foregroundColor(HDTheme.crispOffWhite)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(HDTheme.hardwareBorder, lineWidth: 1)
                )
                .cornerRadius(8)
        }
    }

    private func subdivisionSlider(
        label: String,
        value: Float,
        color: Color,
        onChange: @escaping (Float) -> Void
    ) -> some View {
        HStack(spacing: 10) {
            Text(label)
                .font(.system(.caption, design: .monospaced))
                .foregroundColor(HDTheme.crispOffWhite)
                .frame(width: 112, alignment: .leading)
            Slider(
                value: Binding(
                    get: { Double(value) },
                    set: { onChange(Float($0)) }
                ),
                in: 0...1
            )
            .tint(color)
            Text("\(Int(round(value * 100)))%")
                .font(.system(.caption2, design: .monospaced).weight(.bold))
                .foregroundColor(value > 0.01 ? color : HDTheme.mutedSlateText)
                .frame(width: 36, alignment: .trailing)
        }
    }

    private func consoleCard<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HDTheme.mutedSlateText)
            content()
        }
        .padding(12)
        .background(HDTheme.consoleSurface)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(HDTheme.hardwareBorder, lineWidth: 1)
        )
        .cornerRadius(14)
    }
}
