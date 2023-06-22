package com.coughextractor.recorder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.coughextractor.Accelerometer
import com.google.gson.GsonBuilder
import java.io.InputStream
import java.util.UUID
import kotlin.math.abs

class AccelerometerThread : Thread() {
    var threshold: Int = 35
    var isCough: Boolean = false
    var isConnected: Boolean = false
    lateinit var bluetoothAdapter: BluetoothAdapter
    private var isStopped = false
    private var bluetoothSocket: BluetoothSocket? = null
    var currentAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    private var prevAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    var device: BluetoothDevice? = null
    lateinit var onAccelerometerUpdate: (amplitudes: Short) -> Unit
    lateinit var onAccelerometerXUpdate: (amplitudes: Short) -> Unit
    lateinit var onAccelerometerYUpdate: (amplitudes: Short) -> Unit
    private val coughDeviceId = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun run() {
        if (!isStopped) {
            readingLoop()
        }
    }

    private fun connectToDevice() {
        if (device?.uuids !== null) {
            try {
                bluetoothSocket = device!!.createRfcommSocketToServiceRecord(coughDeviceId)
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
                val x = abs(percent(abs(prevAccelerometer.Xa), abs(currentAccelerometer.Xa)))
                val y = abs(percent(abs(prevAccelerometer.Ya), abs(currentAccelerometer.Ya)))
                val adc =
                    abs(percent(abs(prevAccelerometer.ADC), abs(currentAccelerometer.ADC)))
                Log.e("X", x.toString())
                Log.e("Y", y.toString())
                Log.e("ADC", adc.toString())
                Log.e("threshold", threshold.toString())

                this.isCough = (x >= threshold || y >= threshold) && adc >= threshold
                onAccelerometerUpdate(currentAccelerometer.ADC.toShort())
                onAccelerometerXUpdate(currentAccelerometer.Xa.toShort())
                onAccelerometerYUpdate(currentAccelerometer.Ya.toShort())
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

    private fun percent(a: Int, b: Int): Int {
        if (a != 0 && b != 0) {
            return ((b - a) * 100 / a)
        }
        return 0;
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
