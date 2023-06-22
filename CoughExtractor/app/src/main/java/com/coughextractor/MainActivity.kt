package com.coughextractor

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.text.Spanned
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProviders
import com.coughextractor.databinding.ActivityMainBinding
import com.coughextractor.recorder.AmplitudeCoughRecorder
import com.coughextractor.recorder.Examination
import com.coughextractor.recorder.ExaminationsResponse
import com.coughextractor.recorder.MyBinder
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


private const val REQUEST_PERMISSION_CODE = 200

class InputFilterMinMax : InputFilter {
    private var min: Int
    private var max: Int

    constructor(min: Int, max: Int) {
        this.min = min
        this.max = max
    }

    constructor(min: String, max: String) {
        this.min = min.toInt()
        this.max = max.toInt()
    }

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): String? {
        try {
            val input = (dest.toString() + source.toString()).toInt()
            if (isInRange(min, max, input)) return null
        } catch (nfe: NumberFormatException) {
        }
        return ""
    }

    private fun isInRange(a: Int, b: Int, c: Int): Boolean {
        return if (b > a) c >= a && c <= b else c >= b && c <= a
    }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var serviceConnection: ServiceConnection? = null

    var examinations: List<Examination> = listOf()
    var userId: Int = -1
    var examinationNames: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val service = Intent(this, AmplitudeCoughRecorder::class.java)

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as MyBinder
                val viewModel =
                    ViewModelProviders.of(this@MainActivity).get(MainViewModel::class.java)
                viewModel.bindService(service)
                val extras = intent.extras
                if (extras != null) {
                    val token = extras.getString("token").toString()
                    viewModel.token = token
                    userId = extras.getInt("userId", -1)

                    requestPermissions(permissions, REQUEST_PERMISSION_CODE)

                    runOnUiThread {
                        runUi(viewModel)
                    }
                    viewModel.baseDir = externalCacheDir?.absolutePath ?: ""

                    val ref = this@MainActivity
                    GlobalScope.launch(Dispatchers.IO) {
                        val host = getSharedPreferences("Host", MODE_PRIVATE).getString("Host", null)
                        val url = URL("http://$host/api/examinations/")
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

                                examinationsResponse.examinations.forEach {
                                    if (it.examination_name.isNotEmpty()) {
                                        examinationNames.add(it.examination_name)
                                    }
                                }
                                examinations = examinationsResponse.examinations

                                if (examinationNames.isNotEmpty()) {
                                    configureExaminationSelector(ref, viewModel, binder)
                                }
                            }
                        } else {
                            Log.e("HTTPURLCONNECTION_ERROR", responseCode.toString())
                        }
                        viewModel.bindService(binder)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val viewModel =
                    ViewModelProviders.of(this@MainActivity).get(MainViewModel::class.java)
                viewModel.unbindService()
            }
        }
        bindService(service, serviceConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun configureExaminationSelector(
        ref: MainActivity,
        viewModel: MainViewModel,
        binder: MyBinder
    ) {
        val staticSpinner =
            findViewById<View>(R.id.examinations) as Spinner

        val staticAdapter = ArrayAdapter(
            ref,
            R.layout.support_simple_spinner_dropdown_item,
            examinationNames
        )
        staticAdapter.setDropDownViewResource(R.layout.support_simple_spinner_dropdown_item)

        staticSpinner.adapter = staticAdapter
        viewModel.examination = examinations.first()
        val sp =
            getSharedPreferences("selectedExamination", MODE_PRIVATE)
        val selectedExaminationName = sp.getString("examination", null)
        val index =
            examinationNames.indexOf(selectedExaminationName)
        if (index >= 0) {
            staticSpinner.setSelection(index)
            viewModel.examination =
                examinations.first { it.examination_name === staticSpinner.getItemAtPosition(index) }
        }

        staticSpinner.onItemSelectedListener =
            object : OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>, view: View,
                    position: Int, id: Long
                ) {
                    val selectedExamination = examinations.first {
                        it.examination_name === parent.getItemAtPosition(
                            position
                        )
                    }
                    viewModel.examination =
                        selectedExamination

                    val editor = sp.edit()
                    editor.putString(
                        "examination",
                        selectedExamination.examination_name
                    )
                    editor.apply()
                    viewModel.bindService(binder)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // TODO Auto-generated method stub
                }
            }
    }

    private fun runUi(viewModel: MainViewModel) {
        val binding: ActivityMainBinding =
            DataBindingUtil.setContentView(
                this@MainActivity,
                R.layout.activity_main
            )
        binding.viewModel = viewModel
        binding.lifecycleOwner = this@MainActivity

        val et = findViewById<View>(R.id.accelerometerThreshold) as EditText
        et.filters = arrayOf<InputFilter>(InputFilterMinMax("1", "100"))

        val audioChart = findViewById<View>(R.id.chart) as LineChart
        val amplitudesEntries: MutableList<Entry> =
            ArrayList(viewModel.amplitudesLength)
        val amplitudesDataSet =
            LineDataSet(
                amplitudesEntries,
                getString(R.string.chart_amplitude_label)
            )
        amplitudesDataSet.setDrawCircles(false)
        amplitudesDataSet.lineWidth = 2.0f
        amplitudesDataSet.color = ColorTemplate.MATERIAL_COLORS[0]

        val amplitudeThresholdEntries: MutableList<Entry> =
            ArrayList(viewModel.amplitudesLength)
        val amplitudeThresholdDataSet =
            LineDataSet(
                amplitudeThresholdEntries,
                getString(R.string.amplitude_label)
            )
        amplitudeThresholdDataSet.setDrawCircles(false)
        amplitudeThresholdDataSet.lineWidth = 2.0f
        amplitudeThresholdDataSet.color = ColorTemplate.MATERIAL_COLORS[2]

        val amplitudesDataSetLineData =
            LineData(amplitudesDataSet, amplitudeThresholdDataSet)
        audioChart.data = amplitudesDataSetLineData

        val accelerometerChart =
            findViewById<View>(R.id.accelerometerChart) as LineChart
        val accelerometerEntries: MutableList<Entry> =
            ArrayList(viewModel.amplitudesLength)
        val accelerometerDataSet =
            LineDataSet(
                accelerometerEntries,
                getString(R.string.chart_accelerometer_label)
            )
        accelerometerDataSet.setDrawCircles(false)
        accelerometerDataSet.lineWidth = 2.0f
        accelerometerDataSet.color = ColorTemplate.MATERIAL_COLORS[0]
        accelerometerDataSet.addEntry(Entry(0.0f, 0.0f))

        val accelerometerDataSetLineData =
            LineData(accelerometerDataSet)
        accelerometerChart.data = accelerometerDataSetLineData

        val accelerometerXChart =
            findViewById<View>(R.id.accelerometerXChart) as LineChart
        val accelerometerXEntries: MutableList<Entry> =
            ArrayList(viewModel.amplitudesLength)
        val accelerometerXDataSet =
            LineDataSet(
                accelerometerXEntries,
                getString(R.string.x_chart_accelerometer_label)
            )
        accelerometerXDataSet.setDrawCircles(false)
        accelerometerXDataSet.lineWidth = 2.0f
        accelerometerXDataSet.color = ColorTemplate.MATERIAL_COLORS[0]
        accelerometerXDataSet.addEntry(Entry(0.0f, 0.0f))

        val accelerometerXDataSetLineData =
            LineData(accelerometerXDataSet)
        accelerometerXChart.data = accelerometerXDataSetLineData

        val accelerometerYChart =
            findViewById<View>(R.id.accelerometerYChart) as LineChart
        val accelerometerYEntries: MutableList<Entry> =
            ArrayList(viewModel.amplitudesLength)
        val accelerometerYDataSet =
            LineDataSet(
                accelerometerYEntries,
                getString(R.string.y_chart_accelerometer_label)
            )
        accelerometerYDataSet.setDrawCircles(false)
        accelerometerYDataSet.lineWidth = 2.0f
        accelerometerYDataSet.color = ColorTemplate.MATERIAL_COLORS[0]
        accelerometerYDataSet.addEntry(Entry(0.0f, 0.0f))

        val accelerometerYDataSetLineData =
            LineData(accelerometerYDataSet)
        accelerometerYChart.data = accelerometerYDataSetLineData

        viewModel.soundAmplitudeObservable.observe(this@MainActivity) { value ->
            amplitudeThresholdDataSet.clear()
            if (value != null) {
                amplitudeThresholdDataSet.addEntry(
                    Entry(0.0f, value.toFloat())
                )
                amplitudeThresholdDataSet.addEntry(
                    Entry(viewModel.amplitudesLength.toFloat(), value.toFloat())
                )
            }
            amplitudeThresholdDataSet.notifyDataSetChanged()
            amplitudesDataSetLineData.notifyDataChanged()
            audioChart.notifyDataSetChanged()
            audioChart.invalidate()
        }

        timer("Chart Updater", period = 1000 / 24) {
            runOnUiThread {
                synchronized(viewModel.amplitudesLock) {
                    val amplitudes = viewModel.amplitudes.value!!
                    amplitudesDataSet.clear()
                    for (amplitude in amplitudes.withIndex()) {
                        amplitudesDataSet.addEntry(
                            Entry(
                                /* x = */ amplitude.index.toFloat(),
                                /* y = */ amplitude.value
                            )
                        )
                    }

                    amplitudesDataSet.notifyDataSetChanged()
                    amplitudesDataSetLineData.notifyDataChanged()
                    audioChart.notifyDataSetChanged()
                    audioChart.invalidate()
                }
                synchronized(viewModel.accelerometerLock) {
                    val accelerometry = viewModel.accelerometry.value!!
                    accelerometerDataSet.clear()
                    for (accelerometr in accelerometry.withIndex()) {
                        accelerometerDataSet.addEntry(
                            Entry(
                                /* x = */ accelerometr.index.toFloat(),
                                /* y = */ accelerometr.value
                            )
                        )
                    }

                    accelerometerDataSet.notifyDataSetChanged()
                    accelerometerDataSetLineData.notifyDataChanged()
                    accelerometerChart.notifyDataSetChanged()
                    accelerometerChart.invalidate()
                }

                synchronized(viewModel.accelerometerLock) {
                    val accelerometry = viewModel.accelerometryX.value!!
                    accelerometerXDataSet.clear()
                    for (accelerometr in accelerometry.withIndex()) {
                        accelerometerXDataSet.addEntry(
                            Entry(
                                /* x = */ accelerometr.index.toFloat(),
                                /* y = */ accelerometr.value
                            )
                        )
                    }

                    accelerometerXDataSet.notifyDataSetChanged()
                    accelerometerXDataSetLineData.notifyDataChanged()
                    accelerometerXChart.notifyDataSetChanged()
                    accelerometerXChart.invalidate()
                }

                synchronized(viewModel.accelerometerLock) {
                    val accelerometry = viewModel.accelerometryY.value!!
                    accelerometerYDataSet.clear()
                    for (accelerometr in accelerometry.withIndex()) {
                        accelerometerYDataSet.addEntry(
                            Entry(
                                /* x = */ accelerometr.index.toFloat(),
                                /* y = */ accelerometr.value
                            )
                        )
                    }

                    accelerometerYDataSet.notifyDataSetChanged()
                    accelerometerYDataSetLineData.notifyDataChanged()
                    accelerometerYChart.notifyDataSetChanged()
                    accelerometerYChart.invalidate()
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

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection!!)
    }
}
