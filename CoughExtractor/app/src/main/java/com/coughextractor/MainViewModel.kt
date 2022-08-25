package com.coughextractor

import android.util.Log
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlin.collections.ArrayList
import kotlin.math.roundToInt

import com.coughextractor.device.CoughDevice
import com.coughextractor.recorder.AmplitudeCoughRecorder
import com.coughextractor.recorder.AuthResponse
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

private const val TAG = "MainViewModel"

class MainViewModel @ViewModelInject constructor(
    val coughRecorder: AmplitudeCoughRecorder,
    val coughDevice: CoughDevice)
    : ViewModel() {

    init {
        coughRecorder.onAmplitudesUpdate = { amplitudes ->
            synchronized(amplitudesLock) {
                val prevValue = this@MainViewModel.amplitudes.value!!
                val indices = 0 until coughRecorder.audioBufferSize step amplitudesStep

                if (prevValue.count() >= amplitudesLength) {
                    for (index in indices) {
                        prevValue.removeAt(0)
                    }
                }

                for (index in indices) {
                    prevValue.add(amplitudes[index].toFloat())
                }
                this@MainViewModel.amplitudes.postValue(prevValue)
            }
        }
        coughRecorder.amplitudeThreshold = 100
        authorization()
    }

    var baseDir: String = ""

    var amplitude: String
        get() {
            return coughRecorder.amplitudeThreshold.toString()
        }
        set(value) {
            coughRecorder.amplitudeThreshold = value.toIntOrNull()
            amplitudeObservable.postValue(coughRecorder.amplitudeThreshold)
        }
    val amplitudeObservable: MutableLiveData<Int?> by lazy {
        MutableLiveData<Int?>(coughRecorder.amplitudeThreshold)
    }

    val amplitudesLock = Object()
    private val amplitudesTimeLengthSec = 6
    private val amplitudesStep = 1500
    private val amplitudesPerRead = (0 until coughRecorder.audioBufferSize step amplitudesStep).count()
    private val amplitudesPerReadTimeSec = coughRecorder.audioBufferSize.toDouble() / coughRecorder.sampleRate.toDouble()
    val amplitudesLength = (amplitudesTimeLengthSec * amplitudesPerRead / amplitudesPerReadTimeSec).roundToInt()
    val amplitudes: MutableLiveData<ArrayList<Float>> by lazy {
        MutableLiveData<ArrayList<Float>>(ArrayList(amplitudesLength))
    }

    val isRecording: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    override fun onCleared() {
        super.onCleared()
        coughRecorder.stop()
    }

    fun powerClick() {
        if (!coughRecorder.isRecording) {
            amplitudes.postValue(ArrayList())
            val localDate = LocalDateTime.now().atZone(ZoneId.of("UTC"))
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
            val fileName = "sample_${coughDevice.deviceId}_${localDate.format(formatter)}"
            coughRecorder.fileName = "${baseDir}/${fileName}"
            coughRecorder.start()
        } else {
            coughRecorder.stop()
        }
        isRecording.postValue(coughRecorder.isRecording)
    }

    private fun authorization() {
        val jsonObject = JSONObject()
        jsonObject.put("username", "admin")
        jsonObject.put("password", "admin1")

        // Convert JSONObject to String
        val jsonObjectString = jsonObject.toString()

        GlobalScope.launch(Dispatchers.IO) {
            val url = URL("https://cough.bfsoft.su/api-token-auth/")
            val httpURLConnection = url.openConnection() as HttpsURLConnection
            httpURLConnection.requestMethod = "POST"
            httpURLConnection.setRequestProperty(
                "Content-Type",
                "application/json"
            ) // The format of the content we're sending to the server
            httpURLConnection.setRequestProperty(
                "Accept",
                "application/json"
            ) // The format of response we want to get from the server
            httpURLConnection.doInput = true
            httpURLConnection.doOutput = true

            val out = BufferedWriter(OutputStreamWriter(httpURLConnection.outputStream))
            out.write(jsonObjectString)
            out.close()

            // Check if the connection is successful
            val responseCode = httpURLConnection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = httpURLConnection.inputStream.bufferedReader()
                    .use { it.readText() }  // defaults to UTF-8
                withContext(Dispatchers.Main) {

                    // Convert raw JSON to pretty JSON using GSON library
                    val gson = GsonBuilder().setPrettyPrinting().create()
                    val prettyJson = gson.toJson(JsonParser.parseString(response))
                    val authResponse = gson.fromJson(prettyJson, AuthResponse::class.java)
                    coughRecorder.token = authResponse.token
                }
            } else {
                Log.e("HTTPURLCONNECTION_ERROR", responseCode.toString())
            }
        }
    }
}
