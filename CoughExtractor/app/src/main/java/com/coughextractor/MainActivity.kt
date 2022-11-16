package com.coughextractor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.databinding.DataBindingUtil
import com.coughextractor.databinding.ActivityMainBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import dagger.hilt.android.AndroidEntryPoint
import kotlin.concurrent.timer


private const val TAG = "MainActivity"

private const val REQUEST_PERMISSION_CODE = 200

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras
        if (extras != null) {
            viewModel.coughRecorder.token = extras.getString("token").toString()
            //The key argument here must match that used in the other activity
        }

        viewModel.baseDir = externalCacheDir?.absolutePath ?: ""

        requestPermissions(permissions, REQUEST_PERMISSION_CODE)

        val binding: ActivityMainBinding =
            DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this

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
                                amplitude.index.toFloat(), amplitude.value
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

    private var permissionToRecordAccepted = false
    private var permissions: Array<String> =
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH)

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_PERMISSION_CODE) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }
}
