package com.coughextractor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.databinding.DataBindingUtil

import kotlin.concurrent.timer

import dagger.hilt.android.AndroidEntryPoint

import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate

import com.coughextractor.databinding.ActivityMainBinding
import com.coughextractor.device.CoughDeviceError

enum class MainActivityRequestCodes(val code: Int) {
    EnableBluetooth(1)
}

private const val TAG = "MainActivity"

private const val REQUEST_PERMISSION_CODE = 200

@AndroidEntryPoint
class MainActivity() : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.baseDir = externalCacheDir?.absolutePath ?: ""

        requestPermissions(permissions, REQUEST_PERMISSION_CODE)

        val binding: ActivityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this

        val chart = findViewById<View>(R.id.chart) as LineChart

        val amplitudesEntries: MutableList<Entry> = ArrayList(viewModel.amplitudesLength)
        val amplitudesDataSet = LineDataSet(amplitudesEntries, getString(R.string.chart_amplitude_label))
        amplitudesDataSet.setDrawCircles(false)
        amplitudesDataSet.color = ColorTemplate.VORDIPLOM_COLORS[0]

        val amplitudeThresholdEntries: MutableList<Entry> = ArrayList(viewModel.amplitudesLength)
        val amplitudeThresholdDataSet = LineDataSet(amplitudeThresholdEntries, getString(R.string.amplitude_label))
        amplitudeThresholdDataSet.setDrawCircles(false)
        amplitudeThresholdDataSet.color = ColorTemplate.VORDIPLOM_COLORS[1]

        val amplitudesDataSetLineData = LineData(amplitudesDataSet, amplitudeThresholdDataSet)
        chart.data = amplitudesDataSetLineData

        viewModel.amplitudeObservable.observe(this) {
            val amplitudeThreshold = viewModel.coughRecorder.amplitudeThreshold
            if (amplitudeThreshold != null) {
                amplitudeThresholdDataSet.addEntry(Entry(0.0f, amplitudeThreshold.toFloat()))
                amplitudeThresholdDataSet.addEntry(Entry(viewModel.amplitudesLength.toFloat(), amplitudeThreshold.toFloat()))
            } else {
                amplitudeThresholdDataSet.clear()
            }

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
                        amplitudesDataSet.addEntry(Entry(amplitude.index.toFloat(), amplitude.value))
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
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
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
