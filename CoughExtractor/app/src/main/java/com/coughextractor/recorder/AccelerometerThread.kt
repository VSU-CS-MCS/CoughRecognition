package com.coughextractor.recorder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.coughextractor.Accelerometer
import com.google.gson.GsonBuilder
import java.io.InputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AccelerometerThread : Thread() {
    lateinit var bluetoothAdapter: BluetoothAdapter
    private var isStopped = false
    private var bluetoothSocket: BluetoothSocket? = null
    var currentAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    private var prevAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    var isCough = AtomicBoolean(false)
    var device: BluetoothDevice? = null
    lateinit var onAccelerometryUpdate: (amplitudes: Short) -> Unit

    init {

    }

    private fun connectToDevice() {
        for (device in bluetoothAdapter.bondedDevices!!) {
            if (device.name == "HC-COUGH") {
                this.device = device
            }
        }
        if (device !== null) {
            val id: UUID = device?.uuids?.get(0)!!.uuid
            bluetoothSocket = device!!.createRfcommSocketToServiceRecord(id)
            bluetoothSocket?.connect()
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

    override fun run() {
        if (!isStopped) {
            readingLoop()
        }
    }

    override fun interrupt() {
        super.interrupt()
        isStopped = true
        device = null
        bluetoothSocket = null
    }

    private fun readingLoop() {
        connectToDevice()
        if (bluetoothSocket == null) {
            return
        }
        val inputStream: InputStream = bluetoothSocket!!.inputStream!!
        var string = ""
        try {
            while (device !== null) {
                string = inputStream.readUpToChar('\r')
                if (string.endsWith("=") || !string.contains("Xa=")
                    || !string.contains("Ya=") || !string.contains("X=")
                    || !string.contains("Y=") || !string.contains("ADC=")
                ) {
                    sleep(5)
                    continue
                }
                val builder = java.lang.StringBuilder()
                builder.append('{')
                string = string.trim(' ', '\"', '\n', '\r')
                string.replace("=".toRegex(), " : ").also { string = it }
                string.replace("\t".toRegex(), ",").also { string = it }
                builder.append(string)
                builder.append('}')

                val gson = GsonBuilder().setPrettyPrinting().create()
                currentAccelerometer =
                    gson.fromJson(builder.toString(), Accelerometer::class.java)
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
                onAccelerometryUpdate(currentAccelerometer.ADC.toShort())
                this.prevAccelerometer = currentAccelerometer
                sleep(1)
            }
        } catch (e: Exception) {
            println(string)
            if (!isStopped) {
                readingLoop()
            }
        }
    }
}
