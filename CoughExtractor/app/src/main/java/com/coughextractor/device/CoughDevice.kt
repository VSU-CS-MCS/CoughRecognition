package com.coughextractor.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.*
import javax.inject.Inject

enum class CoughDeviceError {
    BluetoothDisabled,
    BluetoothAdapterNotFound,
    CoughDeviceNotFound,
}

val coughDeviceId = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
const val coughDeviceName = "HC-COUGH"
const val TAG = "CoughDevice"

class CoughDevice @Inject constructor(
    private val parser: CoughDeviceDataParser,
) {

    val deviceId = "0"

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var connectThread: ConnectThread? = null

    fun connect(): CoughDeviceError? {
        val bluetoothError = checkBluetooth()
        if (bluetoothError !== null) {
            return bluetoothError
        }

        val coughDevice = bluetoothAdapter!!.bondedDevices.singleOrNull {
            it.name == coughDeviceName
        }
        if (coughDevice === null) {
            return CoughDeviceError.CoughDeviceNotFound
        }

        connectThread = ConnectThread(coughDevice)
        connectThread!!.run()

        return null
    }

    fun disconnect() {
        try {
            connectThread?.cancel()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close the client socket", e)
        }
    }

    private fun checkBluetooth(): CoughDeviceError? {
        if (bluetoothAdapter == null) {
            return CoughDeviceError.BluetoothAdapterNotFound
        }

        if (!bluetoothAdapter.isEnabled) {
            return CoughDeviceError.BluetoothDisabled
        }

        return null
    }

    private inner class ConnectThread(
        val device: BluetoothDevice,
    ) : Thread() {

        private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(coughDeviceId)
        }

        override fun run() {
            socket?.use { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                socket.connect()

                val reader = socket.inputStream.bufferedReader()

                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Couldn't read data", e)
                        break
                    }
                    val deviceData = try {
                        parser.parseLine(line)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Couldn't parse data", e)
                    }
                    Log.v(TAG, line)
                }
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }
}
