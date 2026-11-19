package com.example.midi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

sealed interface MidiHardwareEvent {
    data class NoteOn(val midiNote: Int, val velocity: Int, val channel: Int) : MidiHardwareEvent
    data class NoteOff(val midiNote: Int, val channel: Int) : MidiHardwareEvent
    data class ControlChange(val cc: Int, val value: Int, val channel: Int) : MidiHardwareEvent
    data object AllNotesOff : MidiHardwareEvent
}

data class MidiDeviceEntry(
    val id: Int,
    val name: String,
    val manufacturer: String,
    val isBluetooth: Boolean,
    val isConnected: Boolean
)

data class BleScanDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isMidiService: Boolean
)

data class MidiConnectionUiState(
    val isMidiSupported: Boolean = false,
    val availableDevices: List<MidiDeviceEntry> = emptyList(),
    val connectedDeviceName: String? = null,
    val isScanningBle: Boolean = false,
    val discoveredBleDevices: List<BleScanDevice> = emptyList(),
    val lastEventText: String = "No MIDI events yet",
    val statusMessage: String? = null
)

/**
 * Isolated MIDI 1.0 byte-stream parser. Each physical/virtual MIDI output port MUST use
 * its own instance so concurrent traffic across ports never corrupts running status or data bytes.
 */
internal class MidiStreamParser(
    private val onChannelMessage: (status: Int, d1: Int, d2: Int) -> Unit,
    private val onSysEx: () -> Unit = {}
) {
    private var runningStatus: Int = 0
    private var dataByte1: Int = -1
    private var expectedDataBytes: Int = 0
    private var inSysEx: Boolean = false

    @Synchronized
    fun parse(msg: ByteArray, offset: Int, count: Int) {
        val end = (offset + count).coerceAtMost(msg.size)
        for (idx in offset until end) {
            val b = msg[idx].toInt() and 0xFF
            if (b >= 0xF8) {
                // Real-Time message (Clock, Active Sensing, etc.) - ignore without clearing running status
                continue
            }
            if (b and 0x80 != 0) {
                if (b in 0x80..0xEF) {
                    inSysEx = false
                    runningStatus = b
                    dataByte1 = -1
                    val highNibble = b and 0xF0
                    expectedDataBytes = if (highNibble == 0xC0 || highNibble == 0xD0) 1 else 2
                } else if (b == 0xF0) {
                    inSysEx = true
                    runningStatus = 0
                    dataByte1 = -1
                    expectedDataBytes = 0
                    onSysEx()
                } else {
                    // End of SysEx (0xF7) or System Common (0xF1..0xF6) resets running status
                    inSysEx = false
                    runningStatus = 0
                    dataByte1 = -1
                    expectedDataBytes = 0
                }
            } else {
                if (inSysEx || runningStatus == 0 || expectedDataBytes == 0) continue
                if (expectedDataBytes == 1) {
                    onChannelMessage(runningStatus, b, 0)
                    dataByte1 = -1
                } else {
                    if (dataByte1 < 0) {
                        dataByte1 = b
                    } else {
                        val d1 = dataByte1
                        val d2 = b
                        dataByte1 = -1
                        onChannelMessage(runningStatus, d1, d2)
                    }
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        runningStatus = 0
        dataByte1 = -1
        expectedDataBytes = 0
        inSysEx = false
    }
}

class MidiInputController(context: Context) {

    companion object {
        private const val TAG = "MidiInputController"
        private const val CROSS_PORT_DEDUP_WINDOW_NS = 30_000_000L // 30 ms
        private val BLE_MIDI_SERVICE_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700"))
    }

    private data class BoundPort(
        val port: MidiOutputPort,
        val receiver: MidiReceiver
    )

    private data class PortSourceKey(
        val deviceId: Int,
        val portIndex: Int
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val midiManager: MidiManager? =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        } else {
            // Some Android devices still expose MIDI_SERVICE even without feature flag
            appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        }

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(
        MidiConnectionUiState(isMidiSupported = midiManager != null)
    )
    val state: StateFlow<MidiConnectionUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MidiHardwareEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<MidiHardwareEvent> = _events.asSharedFlow()

    private val connectionLock = Any()
    private val openDevices = mutableMapOf<Int, MidiDevice>()
    private val openPorts = mutableMapOf<Int, MutableList<BoundPort>>()
    private val openingDeviceIds = mutableSetOf<Int>()
    private val connectingBleAddresses = mutableSetOf<String>()
    private val knownDeviceInfos = mutableMapOf<Int, MidiDeviceInfo>()
    private val bleDeviceMap = mutableMapOf<String, BluetoothDevice>()

    // Cross-port NoteOn deduplication (protects against multi-port echo on USB/BLE hubs)
    private val dedupLock = Any()
    private val lastNoteOnTimeNs = mutableMapOf<Int, Long>()
    private val lastNoteOnSource = mutableMapOf<Int, PortSourceKey>()

    // Default standalone parser for direct unit testing
    private val testParser = createPortParser(PortSourceKey(-1, 0))

    private var activeBleScanCallback: ScanCallback? = null
    private var deviceCallbackRegistered = false

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            val skipAutoConnect = synchronized(connectionLock) {
                device.type == MidiDeviceInfo.TYPE_BLUETOOTH && connectingBleAddresses.isNotEmpty()
            }
            refreshMidiDevices(autoConnectNewDevice = if (skipAutoConnect) null else device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            val wasOpen = closeDeviceById(device.id)
            if (wasOpen) {
                _events.tryEmit(MidiHardwareEvent.AllNotesOff)
            }
            refreshMidiDevices(autoConnectNewDevice = null)
        }
    }

    private fun createPortParser(source: PortSourceKey): MidiStreamParser {
        return MidiStreamParser(
            onChannelMessage = { status, d1, d2 ->
                dispatchChannelMessage(source, status, d1, d2)
            },
            onSysEx = {
                _state.update { it.copy(lastEventText = "SysEx / MMC message received") }
            }
        )
    }

    private fun createPortReceiver(source: PortSourceKey): MidiReceiver {
        val parser = createPortParser(source)
        return object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                parser.parse(msg, offset, count)
            }
        }
    }

    fun start() {
        val manager = midiManager ?: return
        if (!deviceCallbackRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.registerDeviceCallback(
                        MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                        { runnable -> mainHandler.post(runnable) },
                        deviceCallback
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.registerDeviceCallback(deviceCallback, mainHandler)
                }
                deviceCallbackRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register MIDI callback: ${e.message}")
            }
        }
        refreshMidiDevices(autoConnectNewDevice = null)
    }

    fun refreshMidiDevices(autoConnectNewDevice: MidiDeviceInfo? = null) {
        val manager = midiManager ?: return
        val devices = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
            } else {
                @Suppress("DEPRECATION")
                manager.devices.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate MIDI devices: ${e.message}")
            emptyList()
        }

        val entries: List<MidiDeviceEntry>
        val shouldAutoConnectAll: Boolean
        synchronized(connectionLock) {
            knownDeviceInfos.clear()
            entries = devices.filter { it.outputPortCount > 0 }.map { info ->
                knownDeviceInfos[info.id] = info
                val props = info.properties
                val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
                    ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                    ?: "MIDI Keyboard #${info.id}"
                val mfg = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""
                val isBt = info.type == MidiDeviceInfo.TYPE_BLUETOOTH
                MidiDeviceEntry(
                    id = info.id,
                    name = name,
                    manufacturer = mfg,
                    isBluetooth = isBt,
                    isConnected = openDevices.containsKey(info.id)
                )
            }
            shouldAutoConnectAll = openDevices.isEmpty() && openingDeviceIds.isEmpty() && connectingBleAddresses.isEmpty()
        }

        val connectedName = entries.firstOrNull { it.isConnected }?.name
        _state.update {
            it.copy(
                isMidiSupported = true,
                availableDevices = entries,
                connectedDeviceName = connectedName
            )
        }

        // Auto-connect newly attached USB MIDI devices (like M-VAVE SMK-37 PRO over USB-C)
        if (autoConnectNewDevice != null && autoConnectNewDevice.outputPortCount > 0) {
            connectMidiDevice(autoConnectNewDevice.id)
        } else if (shouldAutoConnectAll && entries.isNotEmpty()) {
            entries.forEach { connectMidiDevice(it.id) }
        }
    }

    fun connectMidiDevice(deviceId: Int) {
        val manager = midiManager ?: return
        val info = synchronized(connectionLock) {
            if (openDevices.containsKey(deviceId) || !openingDeviceIds.add(deviceId)) {
                return
            }
            knownDeviceInfos[deviceId]
        }
        if (info == null) {
            synchronized(connectionLock) { openingDeviceIds.remove(deviceId) }
            return
        }

        manager.openDevice(info, { device ->
            val alreadyOpen = synchronized(connectionLock) {
                openingDeviceIds.remove(deviceId)
                openDevices.containsKey(deviceId)
            }
            if (device == null) {
                _state.update { it.copy(statusMessage = "Could not open MIDI device.") }
                return@openDevice
            }
            if (alreadyOpen) {
                try {
                    device.close()
                } catch (_: Exception) {
                }
                return@openDevice
            }
            attachOpenedDevice(info, device, wireless = info.type == MidiDeviceInfo.TYPE_BLUETOOTH)
        }, mainHandler)
    }

    private fun attachOpenedDevice(info: MidiDeviceInfo, device: MidiDevice, wireless: Boolean) {
        val deviceId = info.id
        val boundPorts = mutableListOf<BoundPort>()
        for (i in 0 until info.outputPortCount) {
            val port = device.openOutputPort(i)
            if (port != null) {
                val receiver = createPortReceiver(PortSourceKey(deviceId, i))
                port.connect(receiver)
                boundPorts.add(BoundPort(port, receiver))
            }
        }
        if (boundPorts.isEmpty()) {
            try {
                device.close()
            } catch (_: Exception) {
            }
            _state.update { it.copy(statusMessage = "No MIDI output ports found on device.") }
            return
        }

        val duplicateDetected = synchronized(connectionLock) {
            if (openDevices.containsKey(deviceId)) {
                true
            } else {
                knownDeviceInfos[deviceId] = info
                openDevices[deviceId] = device
                openPorts[deviceId] = boundPorts
                false
            }
        }

        if (duplicateDetected) {
            boundPorts.forEach { (port, receiver) ->
                try {
                    port.disconnect(receiver)
                    port.close()
                } catch (_: Exception) {
                }
            }
            try {
                device.close()
            } catch (_: Exception) {
            }
            return
        }

        val props = info.properties
        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI Keyboard"
        val prefix = if (wireless) "Connected wirelessly to" else "Connected to"
        _state.update { it.copy(statusMessage = "$prefix $name") }
        refreshMidiDevices()
    }

    fun disconnectMidiDevice(deviceId: Int) {
        if (closeDeviceById(deviceId)) {
            _events.tryEmit(MidiHardwareEvent.AllNotesOff)
        }
        _state.update { it.copy(statusMessage = "MIDI device disconnected") }
        refreshMidiDevices()
    }

    private fun closeDeviceById(deviceId: Int): Boolean {
        val ports: MutableList<BoundPort>?
        val dev: MidiDevice?
        synchronized(connectionLock) {
            openingDeviceIds.remove(deviceId)
            ports = openPorts.remove(deviceId)
            dev = openDevices.remove(deviceId)
        }
        ports?.forEach { (port, receiver) ->
            try {
                port.disconnect(receiver)
                port.close()
            } catch (_: Exception) {
            }
        }
        dev?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        return dev != null || ports != null
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.update {
                it.copy(statusMessage = "Please enable Bluetooth on your device to scan BLE MIDI.")
            }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.update {
                it.copy(statusMessage = "Bluetooth LE scanner unavailable on this device.")
            }
            return
        }

        stopBleScan()
        bleDeviceMap.clear()
        _state.update {
            it.copy(
                isScanningBle = true,
                discoveredBleDevices = emptyList(),
                statusMessage = "Scanning for M-VAVE SMK-37 PRO / BLE MIDI..."
            )
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = try {
                    device.name ?: result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    null
                }
                val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()
                val hasMidiUuid = serviceUuids.any { it == BLE_MIDI_SERVICE_UUID }
                val looksLikeKeyboard = !name.isNullOrBlank() && (
                    name.contains("SMK", ignoreCase = true) ||
                        name.contains("M-VAVE", ignoreCase = true) ||
                        name.contains("MIDI", ignoreCase = true) ||
                        name.contains("SINCO", ignoreCase = true) ||
                        name.contains("CUVAVE", ignoreCase = true)
                    )

                if (name.isNullOrBlank() && !hasMidiUuid) return

                bleDeviceMap[device.address] = device
                val entry = BleScanDevice(
                    address = device.address,
                    name = name ?: "BLE MIDI Device",
                    rssi = result.rssi,
                    isMidiService = hasMidiUuid || looksLikeKeyboard
                )

                _state.update { current ->
                    val updated = current.discoveredBleDevices
                        .filterNot { it.address == entry.address }
                        .plus(entry)
                        .sortedWith(
                            compareByDescending<BleScanDevice> { it.isMidiService }
                                .thenByDescending { it.rssi }
                        )
                    current.copy(discoveredBleDevices = updated)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.update {
                    it.copy(
                        isScanningBle = false,
                        statusMessage = "BLE scan failed (code $errorCode)."
                    )
                }
            }
        }

        activeBleScanCallback = callback
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            // Automatically stop scanning after 12 seconds to preserve battery
            mainHandler.postDelayed({ stopBleScan() }, 12_000L)
        } catch (e: SecurityException) {
            activeBleScanCallback = null
            _state.update {
                it.copy(
                    isScanningBle = false,
                    statusMessage = "Bluetooth permission required to scan BLE MIDI."
                )
            }
        } catch (e: Exception) {
            activeBleScanCallback = null
            _state.update {
                it.copy(
                    isScanningBle = false,
                    statusMessage = e.localizedMessage ?: "Failed to start BLE scan."
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        val cb = activeBleScanCallback ?: return
        activeBleScanCallback = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
        _state.update { it.copy(isScanningBle = false) }
    }

    @SuppressLint("MissingPermission")
    fun connectBleDevice(address: String) {
        val manager = midiManager ?: return
        val btDevice = bleDeviceMap[address] ?: return
        val added = synchronized(connectionLock) { connectingBleAddresses.add(address) }
        if (!added) return

        stopBleScan()
        _state.update { it.copy(statusMessage = "Connecting to BLE MIDI...") }

        try {
            manager.openBluetoothDevice(btDevice, { midiDevice ->
                synchronized(connectionLock) { connectingBleAddresses.remove(address) }
                if (midiDevice == null) {
                    _state.update {
                        it.copy(statusMessage = "Failed to pair BLE MIDI. Make sure keyboard is in pairing/BLE mode.")
                    }
                    return@openBluetoothDevice
                }
                attachOpenedDevice(midiDevice.info, midiDevice, wireless = true)
            }, mainHandler)
        } catch (e: SecurityException) {
            synchronized(connectionLock) { connectingBleAddresses.remove(address) }
            _state.update {
                it.copy(statusMessage = "Bluetooth Connect permission required.")
            }
        } catch (e: Exception) {
            synchronized(connectionLock) { connectingBleAddresses.remove(address) }
            _state.update {
                it.copy(statusMessage = e.localizedMessage ?: "BLE MIDI connection error.")
            }
        }
    }

    fun clearStatusMessage() {
        _state.update { it.copy(statusMessage = null) }
    }

    fun release() {
        stopBleScan()
        val ids = synchronized(connectionLock) { openDevices.keys.toList() }
        ids.forEach { closeDeviceById(it) }
        synchronized(connectionLock) {
            openingDeviceIds.clear()
            connectingBleAddresses.clear()
        }
        if (deviceCallbackRegistered) {
            try {
                midiManager?.unregisterDeviceCallback(deviceCallback)
            } catch (_: Exception) {
            }
            deviceCallbackRegistered = false
        }
    }

    internal fun parseMidiPacket(msg: ByteArray, offset: Int, count: Int) {
        testParser.parse(msg, offset, count)
    }

    private fun dispatchChannelMessage(source: PortSourceKey, status: Int, d1: Int, d2: Int) {
        val type = status and 0xF0
        val channel = status and 0x0F
        when (type) {
            0x90 -> {
                if (d2 > 0) {
                    if (isCrossPortDuplicateNoteOn(source, d1)) return
                    _state.update { it.copy(lastEventText = "Note On: MIDI $d1 (vel $d2)") }
                    _events.tryEmit(MidiHardwareEvent.NoteOn(d1, d2, channel))
                } else {
                    _state.update { it.copy(lastEventText = "Note Off: MIDI $d1") }
                    _events.tryEmit(MidiHardwareEvent.NoteOff(d1, channel))
                }
            }
            0x80 -> {
                _state.update { it.copy(lastEventText = "Note Off: MIDI $d1") }
                _events.tryEmit(MidiHardwareEvent.NoteOff(d1, channel))
            }
            0xA0 -> {
                _state.update { it.copy(lastEventText = "Poly Aftertouch #$d1 = $d2 (ch ${channel + 1})") }
            }
            0xB0 -> {
                if (d1 == 123) {
                    _state.update { it.copy(lastEventText = "All Notes Off (CC #123)") }
                    _events.tryEmit(MidiHardwareEvent.AllNotesOff)
                } else {
                    _state.update { it.copy(lastEventText = "CC #$d1 = $d2 (ch ${channel + 1})") }
                    _events.tryEmit(MidiHardwareEvent.ControlChange(d1, d2, channel))
                }
            }
            0xC0 -> {
                _state.update { it.copy(lastEventText = "Program Change #$d1 (ch ${channel + 1})") }
            }
            0xD0 -> {
                _state.update { it.copy(lastEventText = "Aftertouch $d1 (ch ${channel + 1})") }
            }
            0xE0 -> {
                _state.update { it.copy(lastEventText = "Pitch Bend (ch ${channel + 1})") }
            }
        }
    }

    private fun isCrossPortDuplicateNoteOn(source: PortSourceKey, midiNote: Int): Boolean {
        val nowNs = System.nanoTime()
        synchronized(dedupLock) {
            val prevTime = lastNoteOnTimeNs[midiNote]
            val prevSource = lastNoteOnSource[midiNote]
            if (prevTime != null && prevSource != null && prevSource != source) {
                if (nowNs - prevTime in 0L..CROSS_PORT_DEDUP_WINDOW_NS) {
                    return true
                }
            }
            lastNoteOnTimeNs[midiNote] = nowNs
            lastNoteOnSource[midiNote] = source
            return false
        }
    }
}
