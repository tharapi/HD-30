import CoreMIDI
import Foundation
import SwiftUI
#if canImport(CoreAudioKit) && os(iOS)
import CoreAudioKit
#endif

enum MidiHardwareEvent: Equatable {
    case noteOn(midiNote: Int, velocity: Int, channel: Int)
    case noteOff(midiNote: Int, channel: Int)
    case controlChange(cc: Int, value: Int, channel: Int)
    case allNotesOff
}

struct MidiDeviceEntry: Identifiable, Equatable {
    let id: Int32 // MIDIUniqueID or endpoint index
    let endpoint: MIDIEndpointRef
    let name: String
    let manufacturer: String
    let isBluetooth: Bool
    let isConnected: Bool
}

struct MidiConnectionUiState: Equatable {
    var isMidiSupported: Bool = true
    var availableDevices: [MidiDeviceEntry] = []
    var connectedDeviceName: String? = nil
    var lastEventText: String = "No MIDI events yet"
    var statusMessage: String? = nil
}

/// Isolated MIDI 1.0 byte-stream parser supporting running status and Real-Time messages.
final class MidiStreamParser {
    private let onChannelMessage: (_ status: Int, _ d1: Int, _ d2: Int) -> Void
    private let onSysEx: () -> Void

    private var runningStatus: Int = 0
    private var dataByte1: Int = -1
    private var expectedDataBytes: Int = 0
    private var inSysEx: Bool = false

    init(
        onChannelMessage: @escaping (_ status: Int, _ d1: Int, _ d2: Int) -> Void,
        onSysEx: @escaping () -> Void = {}
    ) {
        self.onChannelMessage = onChannelMessage
        self.onSysEx = onSysEx
    }

    func parse(_ bytes: [UInt8]) {
        for raw in bytes {
            let b = Int(raw)
            if b >= 0xF8 {
                continue
            }
            if (b & 0x80) != 0 {
                if (0x80...0xEF).contains(b) {
                    inSysEx = false
                    runningStatus = b
                    dataByte1 = -1
                    let highNibble = b & 0xF0
                    expectedDataBytes = (highNibble == 0xC0 || highNibble == 0xD0) ? 1 : 2
                } else if b == 0xF0 {
                    inSysEx = true
                    runningStatus = 0
                    dataByte1 = -1
                    expectedDataBytes = 0
                    onSysEx()
                } else {
                    inSysEx = false
                    runningStatus = 0
                    dataByte1 = -1
                    expectedDataBytes = 0
                }
            } else {
                if inSysEx || runningStatus == 0 || expectedDataBytes == 0 { continue }
                if expectedDataBytes == 1 {
                    onChannelMessage(runningStatus, b, 0)
                    dataByte1 = -1
                } else {
                    if dataByte1 < 0 {
                        dataByte1 = b
                    } else {
                        let d1 = dataByte1
                        let d2 = b
                        dataByte1 = -1
                        onChannelMessage(runningStatus, d1, d2)
                    }
                }
            }
        }
    }
}

/// Native Apple CoreMIDI controller supporting USB MIDI and Bluetooth LE MIDI keyboards.
final class MidiInputController {
    private var clientRef = MIDIClientRef()
    private var inputPortRef = MIDIPortRef()
    private var connectedEndpoints: Set<MIDIEndpointRef> = []
    private var isStarted: Bool = false

    var onEvent: ((MidiHardwareEvent) -> Void)?
    var onStateChanged: ((MidiConnectionUiState) -> Void)?

    private var state = MidiConnectionUiState()

    deinit {
        release()
    }

    func start() {
        guard !isStarted else { return }

        let clientStatus = MIDIClientCreateWithBlock("HarmonyDirectorClient" as CFString, &clientRef) { [weak self] notificationPtr in
            let messageID = notificationPtr.pointee.messageID
            if messageID == .msgSetupChanged || messageID == .msgObjectAdded || messageID == .msgObjectRemoved {
                DispatchQueue.main.async {
                    self?.refreshMidiDevices(autoConnect: true)
                }
            }
        }

        guard clientStatus == noErr else {
            state.isMidiSupported = false
            state.statusMessage = "CoreMIDI unavailable (status \(clientStatus))"
            notifyState()
            return
        }

        let portStatus = MIDIInputPortCreateWithProtocol(
            clientRef,
            "HarmonyDirectorIn" as CFString,
            ._1_0,
            &inputPortRef
        ) { [weak self] eventList, _ in
            self?.handleEventList(eventList)
        }

        guard portStatus == noErr else {
            MIDIClientDispose(clientRef)
            clientRef = 0
            state.isMidiSupported = false
            state.statusMessage = "Failed to open MIDI input port (status \(portStatus))"
            notifyState()
            return
        }

        isStarted = true
        state.isMidiSupported = true
        refreshMidiDevices(autoConnect: true)
    }

    func release() {
        guard isStarted else { return }
        for endpoint in connectedEndpoints {
            MIDIPortDisconnectSource(inputPortRef, endpoint)
        }
        connectedEndpoints.removeAll()
        if inputPortRef != 0 {
            MIDIPortDispose(inputPortRef)
            inputPortRef = 0
        }
        if clientRef != 0 {
            MIDIClientDispose(clientRef)
            clientRef = 0
        }
        isStarted = false
    }

    func refreshMidiDevices(autoConnect: Bool = false) {
        guard isStarted else { return }

        let count = MIDIGetNumberOfSources()
        var entries: [MidiDeviceEntry] = []
        var activeEndpoints: Set<MIDIEndpointRef> = []

        for i in 0..<count {
            let endpoint = MIDIGetSource(i)
            guard endpoint != 0 else { continue }
            activeEndpoints.insert(endpoint)

            let name = Self.stringProperty(endpoint, kMIDIPropertyDisplayName)
                ?? Self.stringProperty(endpoint, kMIDIPropertyName)
                ?? "MIDI Source \(i + 1)"
            let manufacturer = Self.stringProperty(endpoint, kMIDIPropertyManufacturer) ?? "External MIDI"
            let uniqueId = Self.intProperty(endpoint, kMIDIPropertyUniqueID) ?? Int32(i + 1)
            let driverOwner = Self.stringProperty(endpoint, kMIDIPropertyDriverOwner) ?? ""
            let isBluetooth = driverOwner.localizedCaseInsensitiveContains("bluetooth")
                || name.localizedCaseInsensitiveContains("bluetooth")
                || name.localizedCaseInsensitiveContains("ble")
                || name.localizedCaseInsensitiveContains("smk")

            if autoConnect && !connectedEndpoints.contains(endpoint) {
                if MIDIPortConnectSource(inputPortRef, endpoint, nil) == noErr {
                    connectedEndpoints.insert(endpoint)
                }
            }

            entries.append(
                MidiDeviceEntry(
                    id: uniqueId,
                    endpoint: endpoint,
                    name: name,
                    manufacturer: manufacturer,
                    isBluetooth: isBluetooth,
                    isConnected: connectedEndpoints.contains(endpoint)
                )
            )
        }

        // Prune removed endpoints
        let stale = connectedEndpoints.subtracting(activeEndpoints)
        for ep in stale {
            MIDIPortDisconnectSource(inputPortRef, ep)
            connectedEndpoints.remove(ep)
        }

        let connectedNames = entries.filter { $0.isConnected }.map { $0.name }
        state.availableDevices = entries
        state.connectedDeviceName = connectedNames.isEmpty ? nil : connectedNames.joined(separator: ", ")
        notifyState()
    }

    func connectDevice(_ entry: MidiDeviceEntry) {
        guard isStarted else { return }
        let status = MIDIPortConnectSource(inputPortRef, entry.endpoint, nil)
        if status == noErr {
            connectedEndpoints.insert(entry.endpoint)
            state.statusMessage = "Connected to \(entry.name)"
        } else {
            state.statusMessage = "Could not connect to \(entry.name) (\(status))"
        }
        refreshMidiDevices(autoConnect: false)
    }

    func disconnectDevice(_ entry: MidiDeviceEntry) {
        guard isStarted else { return }
        MIDIPortDisconnectSource(inputPortRef, entry.endpoint)
        connectedEndpoints.remove(entry.endpoint)
        state.statusMessage = "Disconnected \(entry.name)"
        refreshMidiDevices(autoConnect: false)
    }

    func clearStatusMessage() {
        state.statusMessage = nil
        notifyState()
    }

    private func notifyState() {
        let snapshot = state
        DispatchQueue.main.async { [weak self] in
            self?.onStateChanged?(snapshot)
        }
    }

    private func handleEventList(_ eventList: UnsafePointer<MIDIEventList>) {
        for packetPtr in eventList.unsafeSequence() {
            var packet = packetPtr.pointee
            let wordCount = min(Int(packet.wordCount), 64)
            withUnsafePointer(to: &packet.words) { tuplePtr in
                tuplePtr.withMemoryRebound(to: UInt32.self, capacity: 64) { wordsPtr in
                    for idx in 0..<wordCount {
                        let word = wordsPtr[idx]
                        let messageType = (word >> 28) & 0xF
                        // UMP Message Type 0x2 = 32-bit MIDI 1.0 Channel Voice Message
                        if messageType == 0x2 {
                            let status = Int((word >> 16) & 0xFF)
                            let d1 = Int((word >> 8) & 0xFF)
                            let d2 = Int(word & 0xFF)
                            dispatchChannelMessage(status: status, d1: d1, d2: d2)
                        }
                    }
                }
            }
        }
    }

    func dispatchChannelMessage(status: Int, d1: Int, d2: Int) {
        let command = status & 0xF0
        let channel = (status & 0x0F) + 1

        let event: MidiHardwareEvent?
        let summary: String?

        switch command {
        case 0x90:
            if d2 > 0 {
                event = .noteOn(midiNote: d1, velocity: d2, channel: channel)
                summary = "Note On \(PitchTheory.noteNameWithOctave(d1)) (vel \(d2), ch \(channel))"
            } else {
                event = .noteOff(midiNote: d1, channel: channel)
                summary = "Note Off \(PitchTheory.noteNameWithOctave(d1)) (ch \(channel))"
            }
        case 0x80:
            event = .noteOff(midiNote: d1, channel: channel)
            summary = "Note Off \(PitchTheory.noteNameWithOctave(d1)) (ch \(channel))"
        case 0xB0:
            if d1 == 120 || d1 == 123 {
                event = .allNotesOff
                summary = "All Notes Off (CC #\(d1))"
            } else {
                event = .controlChange(cc: d1, value: d2, channel: channel)
                summary = "CC #\(d1) = \(d2) (ch \(channel))"
            }
        default:
            event = nil
            summary = nil
        }

        if let event {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if let summary {
                    self.state.lastEventText = summary
                    self.onStateChanged?(self.state)
                }
                self.onEvent?(event)
            }
        }
    }

    private static func stringProperty(_ obj: MIDIObjectRef, _ property: CFString) -> String? {
        var param: Unmanaged<CFString>?
        let status = MIDIObjectGetStringProperty(obj, property, &param)
        guard status == noErr, let str = param?.takeRetainedValue() else { return nil }
        let swiftStr = str as String
        return swiftStr.isEmpty ? nil : swiftStr
    }

    private static func intProperty(_ obj: MIDIObjectRef, _ property: CFString) -> Int32? {
        var value: Int32 = 0
        let status = MIDIObjectGetIntegerProperty(obj, property, &value)
        return status == noErr ? value : nil
    }
}

#if canImport(CoreAudioKit) && os(iOS)
/// Native Apple Bluetooth LE MIDI device scanner sheet (supports M-VAVE SMK-37 PRO and standard BLE-MIDI devices).
struct BluetoothMidiCentralSheet: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UINavigationController {
        let btController = CABTMIDICentralViewController()
        btController.title = "Bluetooth LE MIDI"
        return UINavigationController(rootViewController: btController)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}
}
#endif
