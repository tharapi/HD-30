import SwiftUI

struct HarmonyPanelView: View {
    @ObservedObject var viewModel: ConsoleViewModel

    private var baseMidi: Int {
        48 + (viewModel.harmony.octaveShift * 12) // C3..C5 default
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // 1. Voice Selector Card
                consoleCard(title: "TONE VOICE") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 95), spacing: 8)], spacing: 8) {
                        ForEach(ToneVoice.allCases) { voice in
                            let selected = viewModel.harmony.voice == voice
                            Button {
                                viewModel.selectVoice(voice)
                            } label: {
                                VStack(spacing: 2) {
                                    Text(voice.label)
                                        .font(.system(.caption, design: .rounded).weight(.bold))
                                    Text(voice.category)
                                        .font(.system(size: 10))
                                        .opacity(0.75)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 8)
                                .background(selected ? HDTheme.electricSkyContainer : HDTheme.anodizedSlate)
                                .foregroundColor(selected ? HDTheme.electricSky : HDTheme.crispOffWhite)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(selected ? HDTheme.electricSky : HDTheme.hardwareBorder, lineWidth: 1)
                                )
                                .cornerRadius(10)
                            }
                        }
                    }

                    // Tone Gain Slider
                    HStack(spacing: 10) {
                        Image(systemName: "speaker.wave.2")
                            .font(.caption)
                            .foregroundColor(HDTheme.electricSky)
                        Text("Tone Vol")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(HDTheme.mutedSlateText)
                        Slider(
                            value: Binding(
                                get: { Double(viewModel.harmony.toneGain) },
                                set: { viewModel.setToneGain(Float($0)) }
                            ),
                            in: 0...1
                        )
                        .tint(HDTheme.electricSky)
                        Text("\(Int(round(viewModel.harmony.toneGain * 100)))%")
                            .font(.system(.caption, design: .monospaced).weight(.semibold))
                            .foregroundColor(HDTheme.crispOffWhite)
                            .frame(width: 38, alignment: .trailing)
                    }
                    .padding(.top, 4)
                }

                // 2. Intonation, Reference Pitch & Transposition Card
                consoleCard(title: "INTONATION & TUNING") {
                    // Temperament Segmented Toggle
                    HStack(spacing: 8) {
                        ForEach(TemperamentMode.allCases) { mode in
                            let selected = viewModel.harmony.temperament == mode
                            Button {
                                viewModel.selectTemperament(mode)
                            } label: {
                                Text(mode.label)
                                    .font(.system(.caption, design: .monospaced).weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                                    .background(selected ? (mode == .pure ? HDTheme.pitchEmerald.opacity(0.22) : HDTheme.electricSkyContainer) : HDTheme.anodizedSlate)
                                    .foregroundColor(selected ? (mode == .pure ? HDTheme.pitchEmerald : HDTheme.electricSky) : HDTheme.mutedSlateText)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 8)
                                            .stroke(selected ? (mode == .pure ? HDTheme.pitchEmerald : HDTheme.electricSky) : HDTheme.hardwareBorder, lineWidth: 1)
                                    )
                                    .cornerRadius(8)
                            }
                        }
                    }

                    // Pure Just Root Key Picker
                    if viewModel.harmony.temperament == .pure {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("JUST ROOT KEY")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .foregroundColor(HDTheme.mutedSlateText)
                                Spacer()
                                Button {
                                    viewModel.toggleAutoDetectRoot()
                                } label: {
                                    Text(viewModel.harmony.autoDetectRoot ? "AUTO ROOT: ON" : "AUTO ROOT: OFF")
                                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(viewModel.harmony.autoDetectRoot ? HDTheme.pitchEmerald.opacity(0.2) : HDTheme.anodizedSlate)
                                        .foregroundColor(viewModel.harmony.autoDetectRoot ? HDTheme.pitchEmerald : HDTheme.mutedSlateText)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 6)
                                                .stroke(viewModel.harmony.autoDetectRoot ? HDTheme.pitchEmerald : HDTheme.hardwareBorder, lineWidth: 1)
                                        )
                                        .cornerRadius(6)
                                }
                            }

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(0..<12, id: \.self) { pc in
                                        let isEffective = viewModel.harmony.effectiveRootIndex == pc
                                        Button {
                                            viewModel.selectRootKey(pc)
                                        } label: {
                                            Text(PitchTheory.noteNames[pc])
                                                .font(.system(.caption, design: .monospaced).weight(.bold))
                                                .frame(width: 34, height: 30)
                                                .background(isEffective ? HDTheme.pitchEmerald.opacity(0.24) : HDTheme.anodizedSlate)
                                                .foregroundColor(isEffective ? HDTheme.pitchEmerald : HDTheme.crispOffWhite)
                                                .overlay(
                                                    RoundedRectangle(cornerRadius: 7)
                                                        .stroke(isEffective ? HDTheme.pitchEmerald : HDTheme.hardwareBorder, lineWidth: 1)
                                                )
                                                .cornerRadius(7)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Reference Pitch A4
                    HStack(spacing: 6) {
                        Text("A4")
                            .font(.system(.caption, design: .monospaced).weight(.bold))
                            .foregroundColor(HDTheme.mutedSlateText)
                        stepButton("-1") { viewModel.adjustReferencePitch(-1.0) }
                        stepButton("-0.1") { viewModel.adjustReferencePitch(-0.1) }
                        Text(String(format: "%.1f Hz", viewModel.harmony.referencePitchHz))
                            .font(.system(.subheadline, design: .monospaced).weight(.bold))
                            .foregroundColor(HDTheme.electricSky)
                            .frame(minWidth: 74)
                        stepButton("+0.1") { viewModel.adjustReferencePitch(0.1) }
                        stepButton("+1") { viewModel.adjustReferencePitch(1.0) }
                        Spacer(minLength: 2)
                        stepButton("440") { viewModel.setReferencePitchHz(440.0) }
                        stepButton("442") { viewModel.setReferencePitchHz(442.0) }
                    }

                    // Transposition Keys
                    HStack(spacing: 6) {
                        Text("KEY")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(HDTheme.mutedSlateText)
                        ForEach(TranspositionKey.allCases) { key in
                            let selected = viewModel.harmony.transposition == key
                            Button {
                                viewModel.selectTransposition(key)
                            } label: {
                                Text(key.shortLabel)
                                    .font(.system(.caption, design: .monospaced).weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 6)
                                    .background(selected ? HDTheme.warmAmberContainer : HDTheme.anodizedSlate)
                                    .foregroundColor(selected ? HDTheme.warmAmber : HDTheme.crispOffWhite)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 7)
                                            .stroke(selected ? HDTheme.warmAmber : HDTheme.hardwareBorder, lineWidth: 1)
                                    )
                                    .cornerRadius(7)
                            }
                        }
                    }
                }

                // 3. Chord Hold Keyboard Card
                consoleCard(title: "HARMONY KEYBOARD") {
                    HStack(spacing: 8) {
                        Button {
                            viewModel.toggleChordHold()
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: viewModel.harmony.chordHoldEnabled ? "lock.fill" : "hand.tap")
                                Text(viewModel.harmony.chordHoldEnabled ? "HOLD ON" : "MOMENTARY")
                            }
                            .font(.system(.caption2, design: .monospaced).weight(.bold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(viewModel.harmony.chordHoldEnabled ? HDTheme.electricSkyContainer : HDTheme.anodizedSlate)
                            .foregroundColor(viewModel.harmony.chordHoldEnabled ? HDTheme.electricSky : HDTheme.mutedSlateText)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(viewModel.harmony.chordHoldEnabled ? HDTheme.electricSky : HDTheme.hardwareBorder, lineWidth: 1)
                            )
                            .cornerRadius(8)
                        }

                        Button {
                            viewModel.clearHeldNotes()
                        } label: {
                            Text("CLEAR")
                                .font(.system(.caption2, design: .monospaced).weight(.bold))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 7)
                                .background(HDTheme.anodizedSlate)
                                .foregroundColor(HDTheme.downbeatCrimson)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(HDTheme.hardwareBorder, lineWidth: 1)
                                )
                                .cornerRadius(8)
                        }

                        Spacer()

                        HStack(spacing: 6) {
                            stepButton("OCT -") { viewModel.adjustOctaveShift(-1) }
                            Text("\(PitchTheory.noteNameWithOctave(baseMidi))")
                                .font(.system(.caption2, design: .monospaced).weight(.bold))
                                .foregroundColor(HDTheme.crispOffWhite)
                                .frame(width: 28)
                            stepButton("OCT +") { viewModel.adjustOctaveShift(1) }
                        }
                    }

                    // Active Note Telemetry Chips
                    if !viewModel.harmony.activePitchInfos.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(viewModel.harmony.activePitchInfos) { info in
                                    VStack(alignment: .leading, spacing: 1) {
                                        HStack(spacing: 4) {
                                            Text(info.soundingName)
                                                .font(.system(.caption, design: .monospaced).weight(.bold))
                                                .foregroundColor(HDTheme.electricSky)
                                            Text(info.intervalName)
                                                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                                                .foregroundColor(HDTheme.pitchEmerald)
                                        }
                                        Text(String(format: "%.1fHz (%+.1fc)", info.frequencyHz, info.centsOffsetFromEt))
                                            .font(.system(size: 10, design: .monospaced))
                                            .foregroundColor(HDTheme.mutedSlateText)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 5)
                                    .background(HDTheme.lcdMidnight)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 7)
                                            .stroke(HDTheme.lcdBorder, lineWidth: 1)
                                    )
                                    .cornerRadius(7)
                                }
                            }
                        }
                    }

                    // 2-Octave Interactive Piano Keyboard
                    PianoKeyboardView(
                        baseMidi: baseMidi,
                        activeMidis: viewModel.harmony.activeWrittenMidis,
                        chordHoldEnabled: viewModel.harmony.chordHoldEnabled,
                        onPress: { viewModel.onKeyPressed($0) },
                        onRelease: { viewModel.onKeyReleased($0) }
                    )
                    .frame(height: 158)
                }
            }
            .padding(14)
        }
        .background(HDTheme.studioCharcoal)
    }

    private func stepButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .padding(.horizontal, 7)
                .padding(.vertical, 6)
                .background(HDTheme.anodizedSlate)
                .foregroundColor(HDTheme.crispOffWhite)
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(HDTheme.hardwareBorder, lineWidth: 1)
                )
                .cornerRadius(6)
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

private struct PianoKeyboardView: View {
    let baseMidi: Int
    let activeMidis: Set<Int>
    let chordHoldEnabled: Bool
    let onPress: (Int) -> Void
    let onRelease: (Int) -> Void

    // 15 white keys across 2 octaves (C..C)
    private static let whiteOffsets = [0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24]
    // (semitoneOffset, whiteKeyBoundaryIndex)
    private static let blackKeySpecs: [(offset: Int, afterWhiteIndex: Int)] = [
        (1, 0), (3, 1), (6, 3), (8, 4), (10, 5),
        (13, 7), (15, 8), (18, 10), (20, 11), (22, 12)
    ]

    var body: some View {
        GeometryReader { geo in
            let whiteCount = CGFloat(Self.whiteOffsets.count)
            let whiteW = geo.size.width / whiteCount
            let blackW = whiteW * 0.64
            let blackH = geo.size.height * 0.60

            ZStack(alignment: .topLeading) {
                // White keys layer
                HStack(spacing: 1) {
                    ForEach(Self.whiteOffsets, id: \.self) { offset in
                        let midi = baseMidi + offset
                        let isActive = activeMidis.contains(midi)
                        let label = (offset % 12 == 0) ? PitchTheory.noteNameWithOctave(midi) : PitchTheory.pitchClassName(midi)
                        KeyTouchSurface(
                            midi: midi,
                            chordHoldEnabled: chordHoldEnabled,
                            onPress: onPress,
                            onRelease: onRelease
                        ) {
                            ZStack(alignment: .bottom) {
                                RoundedRectangle(cornerRadius: 5)
                                    .fill(isActive ? HDTheme.ivoryKeyPressed : HDTheme.ivoryKey)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 5)
                                            .stroke(isActive ? HDTheme.electricSky : HDTheme.hardwareBorder, lineWidth: isActive ? 2 : 0.75)
                                    )
                                Text(label)
                                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                                    .foregroundColor(isActive ? HDTheme.lcdMidnight : HDTheme.studioCharcoal.opacity(0.7))
                                    .padding(.bottom, 6)
                            }
                        }
                    }
                }

                // Black keys overlay
                ForEach(Self.blackKeySpecs, id: \.offset) { spec in
                    let midi = baseMidi + spec.offset
                    let isActive = activeMidis.contains(midi)
                    let xPos = CGFloat(spec.afterWhiteIndex + 1) * whiteW - (blackW / 2.0)

                    KeyTouchSurface(
                        midi: midi,
                        chordHoldEnabled: chordHoldEnabled,
                        onPress: onPress,
                        onRelease: onRelease
                    ) {
                        ZStack(alignment: .bottom) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(isActive ? HDTheme.ebonyKeyPressed : HDTheme.ebonyKey)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 4)
                                        .stroke(isActive ? HDTheme.electricSky : HDTheme.studioCharcoal, lineWidth: 1)
                                )
                            if isActive {
                                Circle()
                                    .fill(HDTheme.crispOffWhite)
                                    .frame(width: 5, height: 5)
                                    .padding(.bottom, 6)
                            }
                        }
                    }
                    .frame(width: blackW, height: blackH)
                    .offset(x: xPos, y: 0)
                }
            }
        }
        .padding(3)
        .background(HDTheme.studioCharcoal)
        .cornerRadius(8)
    }
}

private struct KeyTouchSurface<Content: View>: View {
    let midi: Int
    let chordHoldEnabled: Bool
    let onPress: (Int) -> Void
    let onRelease: (Int) -> Void
    @ViewBuilder let content: () -> Content

    @State private var isTouching: Bool = false

    var body: some View {
        content()
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        if !isTouching {
                            isTouching = true
                            onPress(midi)
                        }
                    }
                    .onEnded { _ in
                        isTouching = false
                        if !chordHoldEnabled {
                            onRelease(midi)
                        }
                    }
            )
    }
}
