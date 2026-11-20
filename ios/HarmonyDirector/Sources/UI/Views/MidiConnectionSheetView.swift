import SwiftUI

struct MidiConnectionSheetView: View {
    @ObservedObject var viewModel: ConsoleViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showBleMidiScanner = false

    var body: some View {
        NavigationStack {
            List {
                // Live Monitor Section
                Section("MIDI Monitor") {
                    HStack {
                        Text("Status")
                            .foregroundColor(HDTheme.mutedSlateText)
                        Spacer()
                        Text(viewModel.midiState.connectedDeviceName ?? "Not Connected")
                            .font(.system(.caption, design: .monospaced).weight(.bold))
                            .foregroundColor(viewModel.midiState.connectedDeviceName != nil ? HDTheme.pitchEmerald : HDTheme.mutedSlateText)
                    }
                    HStack {
                        Text("Last Event")
                            .foregroundColor(HDTheme.mutedSlateText)
                        Spacer()
                        Text(viewModel.midiState.lastEventText)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(HDTheme.electricSky)
                    }
                    if let statusMsg = viewModel.midiState.statusMessage {
                        Text(statusMsg)
                            .font(.caption)
                            .foregroundColor(HDTheme.warmAmber)
                    }
                }

                // Devices Section (USB & Bluetooth LE MIDI)
                Section("CoreMIDI Devices (USB / Bluetooth LE)") {
                    #if canImport(CoreAudioKit) && os(iOS)
                    Button {
                        showBleMidiScanner = true
                    } label: {
                        Label("Pair Bluetooth LE MIDI Keyboard...", systemImage: "dot.radiowaves.left.and.right")
                            .foregroundColor(HDTheme.electricSky)
                    }
                    #endif

                    Button {
                        viewModel.refreshMidiDevices()
                    } label: {
                        Label("Refresh MIDI Sources", systemImage: "arrow.clockwise")
                    }

                    if viewModel.midiState.availableDevices.isEmpty {
                        Text("No CoreMIDI sources found. Connect a USB MIDI keyboard (via USB-C / Camera Adapter) or tap \"Pair Bluetooth LE MIDI Keyboard\" above.")
                            .font(.caption)
                            .foregroundColor(HDTheme.mutedSlateText)
                    } else {
                        ForEach(viewModel.midiState.availableDevices) { device in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(device.name)
                                        .font(.subheadline.weight(.semibold))
                                    Text("\(device.manufacturer)\(device.isBluetooth ? " · BLE" : " · USB/CoreMIDI")")
                                        .font(.caption2)
                                        .foregroundColor(HDTheme.mutedSlateText)
                                }
                                Spacer()
                                Button(device.isConnected ? "Disconnect" : "Connect") {
                                    if device.isConnected {
                                        viewModel.disconnectMidiDevice(device)
                                    } else {
                                        viewModel.connectMidiDevice(device)
                                    }
                                }
                                .buttonStyle(.bordered)
                                .tint(device.isConnected ? HDTheme.downbeatCrimson : HDTheme.pitchEmerald)
                            }
                        }
                    }
                }

                // MIDI CC Learn & Mappings Section
                Section {
                    if let learning = viewModel.learningTarget {
                        HStack {
                            Text("Listening for CC for \"\(learning.label)\"...")
                                .font(.caption.weight(.bold))
                                .foregroundColor(HDTheme.warmAmber)
                            Spacer()
                            Button("Cancel") {
                                viewModel.cancelMidiLearn()
                            }
                            .font(.caption)
                        }
                    }

                    ForEach(MidiControlTarget.allCases) { target in
                        let assignedCc = viewModel.midiMappings[target] ?? -1
                        let isLearning = viewModel.learningTarget == target
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(target.label)
                                    .font(.subheadline)
                                Text(target.isTrigger ? "Trigger button" : "Continuous knob/slider")
                                    .font(.caption2)
                                    .foregroundColor(HDTheme.mutedSlateText)
                            }
                            Spacer()
                            Text(assignedCc >= 0 ? "CC #\(assignedCc)" : "Unassigned")
                                .font(.system(.caption, design: .monospaced).weight(.semibold))
                                .foregroundColor(assignedCc >= 0 ? HDTheme.electricSky : HDTheme.mutedSlateText)
                            Button(isLearning ? "Wait..." : "Learn") {
                                if isLearning {
                                    viewModel.cancelMidiLearn()
                                } else {
                                    viewModel.startMidiLearn(target)
                                }
                            }
                            .buttonStyle(.bordered)
                            .tint(isLearning ? HDTheme.warmAmber : HDTheme.electricSky)
                        }
                    }

                    Button("Reset SMK-37 PRO Defaults", role: .destructive) {
                        viewModel.resetDefaultMidiMappings()
                    }
                } header: {
                    Text("MIDI CC Mapping (SMK-37 PRO)")
                }
            }
            .navigationTitle("MIDI Setup")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            #if canImport(CoreAudioKit) && os(iOS)
            .sheet(isPresented: $showBleMidiScanner, onDismiss: {
                viewModel.refreshMidiDevices()
            }) {
                BluetoothMidiCentralSheet()
            }
            #endif
        }
        .preferredColorScheme(.dark)
    }
}
