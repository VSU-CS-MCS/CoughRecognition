package com.coughextractor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.databinding.DataBindingUtil
import com.coughextractor.databinding.ActivityMainBinding
import com.coughextractor.device.CoughDeviceError
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.gson.GsonBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.InputStream
import java.util.*
import kotlin.concurrent.timer
import kotlin.math.abs


data class Acelerometer(
    val Xa: Int,
    val Ya: Int,
    val X: Int,
    val Y: Int,
    val ADC: Int
)

enum class MainActivityRequestCodes(val code: Int) {
    EnableBluetooth(1)
}

private const val TAG = "MainActivity"

private const val REQUEST_PERMISSION_CODE = 200

@AndroidEntryPoint
class MainActivity() : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    fun connect(btDevice: BluetoothDevice?): BluetoothSocket? {
        val id: UUID = btDevice?.uuids?.get(0)!!.uuid
        val bts = btDevice.createRfcommSocketToServiceRecord(id)
        bts?.connect()
        return bts
    }

    fun InputStream.readUpToChar(stopChar: Char): String {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.baseDir = externalCacheDir?.absolutePath ?: ""

        requestPermissions(permissions, REQUEST_PERMISSION_CODE)

        val binding: ActivityMainBinding =
            DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val device = bluetoothAdapter.getRemoteDevice("20:16:06:23:08:68")

        var inputStream: InputStream = connect(device)?.inputStream!!
        var string = "";
        var acelerometer: Acelerometer = Acelerometer(0, 0, 0, 0, 0)
        try {
            while (true) {
                string = inputStream.readUpToChar('\r')
                if (string.endsWith("=") || !string.contains("Xa=") || !string.contains("Ya=")
                    || !string.contains("X=") || !string.contains("Y=") || !string.contains("ADC=")
                ) {
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
                val newAcelerometer = gson.fromJson(builder.toString(), Acelerometer::class.java)
                if (abs(acelerometer.Xa - newAcelerometer.Xa) > 1000 || abs(acelerometer.Ya - newAcelerometer.Ya) > 1000) {
                    println(acelerometer)
                }
                acelerometer = newAcelerometer
            }
        } catch (e: Exception) {
            println(string)
        }


        val chart = findViewById<View>(R.id.chart) as LineChart

        val amplitudesEntries: MutableList<Entry> = ArrayList(viewModel.amplitudesLength)
        val amplitudesDataSet =
            LineDataSet(amplitudesEntries, getString(R.string.chart_amplitude_label))
        amplitudesDataSet.setDrawCircles(false)
        amplitudesDataSet.color = ColorTemplate.VORDIPLOM_COLORS[0]

        val amplitudeThresholdEntries: MutableList<Entry> = ArrayList(viewModel.amplitudesLength)
        val amplitudeThresholdDataSet =
            LineDataSet(amplitudeThresholdEntries, getString(R.string.amplitude_label))
        amplitudeThresholdDataSet.setDrawCircles(false)
        amplitudeThresholdDataSet.color = ColorTemplate.VORDIPLOM_COLORS[1]

        val amplitudesDataSetLineData = LineData(amplitudesDataSet, amplitudeThresholdDataSet)
        chart.data = amplitudesDataSetLineData

        viewModel.amplitudeObservable.observe(this) {


            amplitudeThresholdDataSet.clear()
            amplitudeThresholdDataSet.addEntry(Entry(viewModel.amplitudesLength.toFloat(), 0.0f))

            amplitudeThresholdDataSet.notifyDataSetChanged()
            amplitudesDataSetLineData.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.invalidate()
        }

        timer("Chart Updater", period = 1000 / 24) {
            runOnUiThread {
                synchronized(viewModel.amplitudesLock) {
                    val amplitudes = viewModel.amplitudes.value!!
                    amplitudesDataSet.clear()
                    for (amplitude in amplitudes.withIndex()) {
                        amplitudesDataSet.addEntry(
                            Entry(
                                amplitude.index.toFloat(),
                                amplitude.value
                            )
                        )
                    }

                    amplitudesDataSet.notifyDataSetChanged()
                    amplitudesDataSetLineData.notifyDataChanged()
                    chart.notifyDataSetChanged()
                    chart.invalidate()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            MainActivityRequestCodes.EnableBluetooth.code -> {
                when (resultCode) {
                    RESULT_OK -> {
                        connectToDevice()
                    }
                    RESULT_CANCELED -> {
                        Log.w(TAG, "Bluetooth not enabled")
                    }
                }
            }
        }
    }

    private var permissionToRecordAccepted = false
    private var permissions: Array<String> =
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_PERMISSION_CODE) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }

    private fun askToEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, MainActivityRequestCodes.EnableBluetooth.code)
    }

    private fun connectToDevice() {
        when (viewModel.coughDevice.connect()) {
            CoughDeviceError.BluetoothAdapterNotFound -> Log.wtf(TAG, "Bluetooth adapter not found")
            CoughDeviceError.BluetoothDisabled -> askToEnableBluetooth()
            CoughDeviceError.CoughDeviceNotFound -> Log.e(TAG, "Cough device not found")
        }
    }
}
