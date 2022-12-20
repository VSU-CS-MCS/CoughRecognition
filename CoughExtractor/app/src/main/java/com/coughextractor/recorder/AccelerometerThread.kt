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
import kotlin.math.abs

class AccelerometerThread : Thread() {
    var currentAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    private var prevAccelerometer = Accelerometer(0, 0, 0, 0, 0)
    var isCough = AtomicBoolean(false)
    var device: BluetoothDevice? = null

    init {

    }

    private fun connect(): BluetoothSocket? {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        for (device in bluetoothAdapter.bondedDevices) {
            if (device.name == "HC-COUGH") {
                this.device = device
            }
        }
        val id: UUID = device?.uuids?.get(0)!!.uuid
        val bts = device!!.createRfcommSocketToServiceRecord(id)
        bts?.connect()
        return bts
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
        readingLoop()
    }

    private fun readingLoop() {
            val inputStream: InputStream = connect()?.inputStream!!
            var string = ""
            try {
                while (true) {
                    string = inputStream.readUpToChar('\r')
                    if (string.endsWith("=") || !string.contains("Xa=")
                        || !string.contains("Ya=") || !string.contains("X=")
                        || !string.contains("Y=") || !string.contains("ADC=")
                    ) {
                        sleep(50)
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
                    if ((abs(prevAccelerometer.Xa - currentAccelerometer.Xa) > 1000 ||
                        abs(prevAccelerometer.Ya - currentAccelerometer.Ya) > 1000) && abs(prevAccelerometer.ADC - currentAccelerometer.ADC) > 100
                    ) {
                        this.isCough.set(true)
                    } else {
                        this.isCough.set(false)
                    }
                    this.prevAccelerometer = currentAccelerometer
                    sleep(1)
                }
            } catch (e: Exception) {
                println(string)
                readingLoop()
            }
    }
}
