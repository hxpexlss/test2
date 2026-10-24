package de.clickbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Finds a Zwift Click, connects, performs the RideOn handshake and reports
 * shift presses. Reconnects on its own when the connection drops.
 * Callers must hold the Bluetooth permissions before calling [start].
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // the pre-Android-13 GATT calls still work and keep minSdk 26
class ClickConnection(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onStatus: (String) -> Unit,
    private val onShift: (ZwiftProtocol.Shift) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var deviceType = 0
    private var lastPressed: Set<Int> = emptySet()
    private val pendingDescriptors = ArrayDeque<BluetoothGattDescriptor>()

    var isConnected = false
        private set

    fun start() {
        running = true
        scan()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
    }

    private fun scan() {
        if (!running || scanning) return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            onStatus("Bluetooth ist aus")
            handler.postDelayed(::scan, 3000)
            return
        }
        onStatus("Suche Zwift Click … (Taste am Click drücken)")
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(ZwiftProtocol.MANUFACTURER_ID) ?: return
            if (data.isEmpty()) return
            val type = data[0].toInt() and 0xFF
            // The left puck of a Click v2 is locked by Zwift, so we only use v1 or the right puck.
            if (type != ZwiftProtocol.DEVICE_CLICK_V1 && type != ZwiftProtocol.DEVICE_CLICK_V2_RIGHT) return
            if (!scanning) return
            stopScan()
            connect(result.device, type)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus("Bluetooth-Suche fehlgeschlagen ($errorCode), versuche erneut …")
            handler.postDelayed(::scan, 3000)
        }
    }

    private fun connect(device: BluetoothDevice, type: Int) {
        deviceType = type
        onStatus("Verbinde mit ${ZwiftProtocol.deviceName(type)} …")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reconnectLater() {
        isConnected = false
        gatt?.close()
        gatt = null
        if (running) {
            onStatus("Click getrennt, suche erneut …")
            handler.postDelayed(::scan, 2000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && g == gatt) {
                    reconnectLater()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val service = ZwiftProtocol.SERVICE_UUIDS.firstNotNullOfOrNull { g.getService(it) }
                if (service == null) {
                    onStatus("Zwift-Dienst nicht gefunden – Firmware in Zwift Companion aktualisieren?")
                    g.disconnect()
                    return@post
                }
                pendingDescriptors.clear()
                for ((uuid, value) in listOf(
                    ZwiftProtocol.ASYNC_CHAR to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    ZwiftProtocol.SYNC_TX_CHAR to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                )) {
                    val characteristic = service.getCharacteristic(uuid) ?: continue
                    g.setCharacteristicNotification(characteristic, true)
                    val cccd = characteristic.getDescriptor(ZwiftProtocol.CCCD) ?: continue
                    cccd.value = value
                    pendingDescriptors.addLast(cccd)
                }
                writeNextDescriptorOrHandshake(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post { writeNextDescriptorOrHandshake(g) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Android 12 and older.
            val value = characteristic.value ?: return
            handler.post { handleMessage(value) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // Android 13 and newer.
            handler.post { handleMessage(value) }
        }
    }

    private fun writeNextDescriptorOrHandshake(g: BluetoothGatt) {
        val next = pendingDescriptors.removeFirstOrNull()
        if (next != null) {
            g.writeDescriptor(next)
            return
        }
        val service = ZwiftProtocol.SERVICE_UUIDS.firstNotNullOfOrNull { g.getService(it) } ?: return
        val rx = service.getCharacteristic(ZwiftProtocol.SYNC_RX_CHAR) ?: return
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        rx.value = ZwiftProtocol.handshake(deviceType)
        g.writeCharacteristic(rx)
        lastPressed = emptySet()
        isConnected = true
        onStatus("✅ ${ZwiftProtocol.deviceName(deviceType)} verbunden")
    }

    private fun handleMessage(value: ByteArray) {
        val pressed = try {
            ZwiftProtocol.pressedButtons(value)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        // The Click repeats its state; only react when a button goes down.
        val newlyPressed = pressed - lastPressed
        lastPressed = pressed
        for (button in newlyPressed) {
            ZwiftProtocol.actionFor(button)?.let(onShift)
        }
    }
}
