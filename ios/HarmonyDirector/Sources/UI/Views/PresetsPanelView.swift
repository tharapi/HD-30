import SwiftUI

struct PresetsPanelView: View {
    @ObservedObject var viewModel: ConsoleViewModel
    @State private var showSaveSheet = false
    @State private var draftTitle = ""
    @State private var draftNotes = ""

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(HDTheme.mutedSlateText)
                    TextField("Search rehearsal presets...", text: $viewModel.searchQuery)
                        .foregroundColor(HDTheme.crispOffWhite)
                }
                .padding(10)
                .background(HDTheme.anodizedSlate)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(HDTheme.hardwareBorder, lineWidth: 1)
                )
                .cornerRadius(10)

                Button {
                    draftTitle = ""
                    draftNotes = ""
                    showSaveSheet = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                        Text("SAVE")
                            .font(.system(.caption, design: .monospaced).weight(.bold))
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(HDTheme.electricSky)
                    .foregroundColor(HDTheme.studioCharcoal)
                    .cornerRadius(10)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)

            if viewModel.filteredPresets.isEmpty {
                Spacer()
                VStack(spacing: 8) {
                    Image(systemName: "music.note.list")
                        .font(.largeTitle)
                        .foregroundColor(HDTheme.mutedSlateText)
                    Text(viewModel.presets.isEmpty ? "No Saved Presets Yet" : "No Matching Presets")
                        .font(.headline)
                        .foregroundColor(HDTheme.crispOffWhite)
                    Text("Save your current tempo, subdivision mix, voice, and Pure Just Intonation settings on-device.")
                        .font(.caption)
                        .foregroundColor(HDTheme.mutedSlateText)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(viewModel.filteredPresets) { preset in
                            let isActive = viewModel.activePresetId == preset.id
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(preset.title)
                                            .font(.headline)
                                            .foregroundColor(isActive ? HDTheme.electricSky : HDTheme.crispOffWhite)
                                        if !preset.notes.isEmpty {
                                            Text(preset.notes)
                                                .font(.caption)
                                                .foregroundColor(HDTheme.mutedSlateText)
                                                .lineLimit(2)
                                        }
                                    }
                                    Spacer()
                                    Text("\(preset.bpm) BPM · \(preset.safeTimeSignature().label)")
                                        .font(.system(.caption, design: .monospaced).weight(.bold))
                                        .foregroundColor(HDTheme.warmAmber)
                                }

                                Text("\(preset.safeVoice().label) · \(preset.safeTemperament().label) · A4=\(String(format: "%.1f", preset.referencePitchHz))Hz · Key \(preset.safeTransposition().shortLabel)")
                                    .font(.system(.caption2, design: .monospaced))
                                    .foregroundColor(HDTheme.mutedSlateText)

                                HStack(spacing: 8) {
                                    Button("LOAD") {
                                        viewModel.loadPreset(preset)
                                    }
                                    .font(.system(.caption2, design: .monospaced).weight(.bold))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(HDTheme.electricSkyContainer)
                                    .foregroundColor(HDTheme.electricSky)
                                    .cornerRadius(7)

                                    Button("UPDATE") {
                                        viewModel.overwritePreset(preset)
                                    }
                                    .font(.system(.caption2, design: .monospaced).weight(.bold))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(HDTheme.anodizedSlate)
                                    .foregroundColor(HDTheme.crispOffWhite)
                                    .cornerRadius(7)

                                    Spacer()

                                    Button {
                                        viewModel.deletePreset(preset)
                                    } label: {
                                        Image(systemName: "trash")
                                            .font(.caption)
                                            .foregroundColor(HDTheme.downbeatCrimson)
                                            .padding(6)
                                    }
                                }
                            }
                            .padding(12)
                            .background(HDTheme.consoleSurface)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(isActive ? HDTheme.electricSky : HDTheme.hardwareBorder, lineWidth: 1)
                            )
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 16)
                }
            }
        }
        .background(HDTheme.studioCharcoal)
        .sheet(isPresented: $showSaveSheet) {
            NavigationStack {
                Form {
                    Section("Preset Details") {
                        TextField("Piece / Exercise Title", text: $draftTitle)
                        TextField("Rehearsal Notes (optional)", text: $draftNotes)
                    }
                    Section("Captured Console Settings") {
                        Text("Tempo: \(viewModel.metronome.bpm) BPM (\(viewModel.metronome.timeSignature.label))")
                        Text("Voice: \(viewModel.harmony.voice.label) · \(viewModel.harmony.temperament.label)")
                        Text(String(format: "A4 Reference: %.1f Hz · Transposition: %@", viewModel.harmony.referencePitchHz, viewModel.harmony.transposition.shortLabel))
                    }
                }
                .navigationTitle("Save Preset")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showSaveSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            viewModel.saveNewPreset(title: draftTitle, notes: draftNotes)
                            showSaveSheet = false
                        }
                        .disabled(draftTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .preferredColorScheme(.dark)
        }
    }
}
