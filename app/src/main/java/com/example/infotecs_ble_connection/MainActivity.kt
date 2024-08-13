package com.example.infotecs_ble_connection

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.util.UUID
import android.os.Looper

class MainActivity : Activity() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothGattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private lateinit var advertiseCallback: AdvertiseCallback
    private lateinit var logcatTextView: TextView
    private lateinit var deviceAddressTextView: TextView
    private lateinit var startAdvertisingButton: Button
    private lateinit var stopAdvertisingButton: Button

    companion object {
        val SERVICE_UUID = UUID.fromString("e9b49a22-4c8a-4bc1-a163-baf7a7a07b1b")
        val CHARACTERISTIC_UUID = UUID.fromString("e9b49a22-4c8a-4bc1-a163-baf7a7a07b1b")
        const val DATA_BLOCK_SIZE = 160
        const val TRANSMISSION_INTERVAL_MS = 60L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var dataToSend: ByteArray? = null
    private var currentOffset = 0
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logcatTextView = findViewById(R.id.tvLogcat)
        deviceAddressTextView = findViewById(R.id.tvDeviceAddress)
        startAdvertisingButton = findViewById(R.id.btnStartAdvertising)
        stopAdvertisingButton = findViewById(R.id.btnStopAdvertising)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BLE", "Bluetooth is not enabled.")
            appendLog("Bluetooth is not enabled.")
            return
        }

        initializeGattServer()
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                appendLog("Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                appendLog("Advertising failed with error code: $errorCode")
            }
        }

        startAdvertisingButton.setOnClickListener {
            startAdvertising()
        }

        stopAdvertisingButton.setOnClickListener {
            stopAdvertising()
        }
    }

    private fun initializeGattServer() {
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        characteristic.value = ByteArray(4) { 0 }
        service.addCharacteristic(characteristic)
        bluetoothGattServer.addService(service)
    }

    private fun startAdvertising() {
        advertiser.stopAdvertising(advertiseCallback)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)

        dataToSend = ByteArray(1000) { it.toByte() }
        currentOffset = 0

    }

    private fun stopAdvertising() {
        advertiser.stopAdvertising(advertiseCallback)
        appendLog("Advertising stopped.")
    }

    private fun sendNextBlock() {
        val data = dataToSend ?: return

        if (connectedDevices.isEmpty()) {

            handler.postDelayed({ sendNextBlock() }, TRANSMISSION_INTERVAL_MS)
            return
        }

        if (currentOffset >= data.size) {
            appendLog("All data sent.")
            return
        }

        val endOffset = (currentOffset + DATA_BLOCK_SIZE).coerceAtMost(data.size)
        val block = data.copyOfRange(currentOffset, endOffset)
        connectedDevices.forEach { device ->
            bluetoothGattServer.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)?.let { characteristic ->
                characteristic.value = block
                bluetoothGattServer.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
        currentOffset = endOffset

        handler.postDelayed({ sendNextBlock() }, TRANSMISSION_INTERVAL_MS)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("Device connected: ${device.address}")
                connectedDevices.add(device)

                sendNextBlock()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("Device disconnected: ${device.address}")
                connectedDevices.remove(device)

            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                appendLog("Read request for characteristic: ${characteristic.uuid}")

                val value = characteristic.value
                appendLog("Read value: ${value.joinToString(prefix = "bytearray(b'", separator = "', '", postfix = "')") { String.format("%02x", it) }}")
                bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                appendLog("Write request for characteristic: ${characteristic.uuid}")

                characteristic.value = value
                if (responseNeeded) {
                    bluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }

                appendLog("Written value: ${value.joinToString(prefix = "bytearray(b'", separator = "', '", postfix = "')") { String.format("%02x", it) }}")
            }
        }
    }


    private fun appendLog(message: String) {
        runOnUiThread {
            Log.d("BLE_LOG", message)
            val currentText = logcatTextView.text.toString()
            logcatTextView.text = "$currentText\n$message"
        }
    }}
