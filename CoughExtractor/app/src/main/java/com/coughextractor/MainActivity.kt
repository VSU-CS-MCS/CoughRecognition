package com.coughextractor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.databinding.DataBindingUtil
import com.coughextractor.databinding.ActivityMainBinding
import com.coughextractor.recorder.Examination
import com.coughextractor.recorder.ExaminationsResponse
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.common.net.HttpHeaders.USER_AGENT
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.timer


private const val TAG = "MainActivity"

private const val REQUEST_PERMISSION_CODE = 200

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    var examinations: List<Examination> = listOf()
    val viewModel: MainViewModel by viewModels()
    var userId: Int = -1
    var examinationNames: MutableList<String> = mutableListOf()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras
        if (extras != null) {
            val token = extras.getString("token").toString()
            viewModel.coughRecorder.token = token
            userId = extras.getInt("userId", -1)
            val ref = this
            GlobalScope.launch(Dispatchers.IO) {
                val url = URL("http://cough.bfsoft.su/api/examinations/17")
                val httpURLConnection = url.openConnection() as HttpURLConnection
                httpURLConnection.requestMethod = "GET"
                httpURLConnection.setRequestProperty("User-Agent", USER_AGENT)
                httpURLConnection.setRequestProperty("Authorization", "token $token")
                val responseCode = httpURLConnection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = httpURLConnection.inputStream.bufferedReader()
                        .use { it.readText() }  // defaults to UTF-8
                    withContext(Dispatchers.Main) {
                        val gson = GsonBuilder().setPrettyPrinting().create()
                        val prettyJson = gson.toJson(JsonParser.parseString(response))
                        val examinationsResponse =
                            gson.fromJson(prettyJson, ExaminationsResponse::class.java)

                        examinationsResponse.examinations.forEach { it ->
                            if (it.examination_name.isNotEmpty()) {
                                examinationNames.add(it.examination_name)
                            }
                        }
                        examinations = examinationsResponse.examinations

                        if (examinationNames.isNotEmpty()) {
                            val staticSpinner = findViewById<View>(R.id.examinations) as Spinner

                            val staticAdapter = ArrayAdapter(
                                ref,
                                R.layout.support_simple_spinner_dropdown_item,
                                examinationNames
                            )
                            staticAdapter.setDropDownViewResource(R.layout.support_simple_spinner_dropdown_item)

                            staticSpinner.adapter = staticAdapter

                            staticSpinner.onItemSelectedListener = object : OnItemSelectedListener {
                                override fun onItemSelected(
                                    parent: AdapterView<*>, view: View,
                                    position: Int, id: Long
                                ) {
                                    ref.viewModel.coughRecorder.examination = examinations.first {
                                        it.examination_name == parent.getItemAtPosition(position)
                                    }
                                }

                                override fun onNothingSelected(parent: AdapterView<*>?) {
                                    // TODO Auto-generated method stub
                                }
                            }
                        }
                    }
                } else {
                    Log.e("HTTPURLCONNECTION_ERROR", responseCode.toString())
                }
            }
        }

        viewModel.baseDir = externalCacheDir?.absolutePath ?: ""

        requestPermissions(permissions, REQUEST_PERMISSION_CODE)

        val binding: ActivityMainBinding =
            DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this


        val soundChart = findViewById<View>(R.id.chart) as LineChart
        val accelerometerChart = findViewById<View>(R.id.accelerometerChart) as LineChart

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
        soundChart.data = amplitudesDataSetLineData

        viewModel.soundAmplitudeObservable.observe(this) {
            amplitudeThresholdDataSet.clear()
            amplitudeThresholdDataSet.addEntry(Entry(viewModel.amplitudesLength.toFloat(), 0.0f))

            amplitudeThresholdDataSet.notifyDataSetChanged()
            amplitudesDataSetLineData.notifyDataChanged()
            soundChart.notifyDataSetChanged()
            soundChart.invalidate()
        }

        viewModel.accelerometerAmplitudeObservable.observe(this) {
            amplitudeThresholdDataSet.clear()
            amplitudeThresholdDataSet.addEntry(Entry(viewModel.amplitudesLength.toFloat(), 0.0f))

            amplitudeThresholdDataSet.notifyDataSetChanged()
            amplitudesDataSetLineData.notifyDataChanged()
            soundChart.notifyDataSetChanged()
            soundChart.invalidate()
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
                    soundChart.notifyDataSetChanged()
                    soundChart.invalidate()
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
