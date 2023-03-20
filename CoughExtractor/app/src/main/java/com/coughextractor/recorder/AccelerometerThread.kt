package com.coughextractor.recorder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.coughextractor.Accelerometer
import com.google.gson.GsonBuilder
import java.io.InputStream
import java.util.*

class AccelerometerThread : Thread() {
    var isConnected: Boolean = false
    lateinit var bluetoothAdapter: BluetoothAdapter
    private var isStopped = false
    private var bluetoothSocket: BluetoothSocket? = null
    var currentAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    private var prevAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    var device: BluetoothDevice? = null
    lateinit var onAccelerometerUpdate: (amplitudes: Short) -> Unit

    override fun run() {
        if (!isStopped) {
            readingLoop()
        }
    }

    private fun connectToDevice() {
        if (device !== null) {
            val id: UUID = device?.uuids?.get(0)!!.uuid
            bluetoothSocket = device!!.createRfcommSocketToServiceRecord(id)
            try {
                bluetoothSocket?.connect()
            } catch (e: Exception) {
                isConnected = false
                connectToDevice()
            }
        }
    }

    private fun InputStream.readUpToChar(stopChar: Char): String {
        val stringBuilder = StringBuilder()
        var currentChar = this.read().toChar()
        while (currentChar != stopChar) {
            stringBuilder.append(currentChar)
            currentChar = this.read().toChar()
            if (this.available() <= 0) {
                stringBuilder.append(currentChar)
                break
            }
        }
        return stringBuilder.toString()
    }

    override fun interrupt() {
        super.interrupt()
        isStopped = true
        device = null
        bluetoothSocket = null
    }

    private fun readingLoop() {
        connectToDevice()
        isConnected = true
        if (bluetoothSocket == null) {
            return
        }
        val inputStream: InputStream = bluetoothSocket!!.inputStream!!
        var string = ""
        try {
            while (device !== null) {
                string = inputStream.readUpToChar('\r')
                if (checkAccelerometerString(string)) continue
                buildAccelerometer(string)
                Log.e("ACCELEROMETER", currentAccelerometer.toString())
                /*if ((abs(prevAccelerometer.Xa - currentAccelerometer.Xa) > 1000 ||
                            abs(prevAccelerometer.Ya - currentAccelerometer.Ya) > 1000) && abs(
                        prevAccelerometer.ADC - currentAccelerometer.ADC
                    ) > 100
                ) {
                    this.isCough.set(true)
                } else {
                    this.isCough.set(false)
                }*/
                onAccelerometerUpdate(currentAccelerometer.ADC.toShort())
                this.prevAccelerometer = currentAccelerometer
                sleep(1)
            }
        } catch (e: Exception) {
            println(string)
            isConnected = false
            if (!isStopped) {
                readingLoop()
            }
        }
    }

    private fun buildAccelerometer(string: String) {
        val builder = java.lang.StringBuilder()
        builder.append('{')
        var modString = string.trim(' ', '\"', '\n', '\r')
        modString.replace("=".toRegex(), " : ").also { modString = it }
        modString.replace("\t".toRegex(), ",").also { modString = it }
        builder.append(modString)
        builder.append('}')

        val gson = GsonBuilder().setPrettyPrinting().create()
        currentAccelerometer =
            gson.fromJson(builder.toString(), Accelerometer::class.java)
    }

    private fun checkAccelerometerString(string: String): Boolean {
        if (string.endsWith("=") || !string.contains("Xa=")
            || !string.contains("Ya=") || !string.contains("X=")
            || !string.contains("Y=") || !string.contains("ADC=")
        ) {
            sleep(5)
            return true
        }
        return false
    }
}
