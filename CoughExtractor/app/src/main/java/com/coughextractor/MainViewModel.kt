package com.coughextractor

import android.util.Log
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.*

import com.coughextractor.device.CoughDevice
import com.coughextractor.recorder.AmplitudeCoughRecorder

private const val TAG = "MainViewModel"

class MainViewModel @ViewModelInject constructor(
    val coughRecorder: AmplitudeCoughRecorder,
    val coughDevice: CoughDevice)
    : ViewModel() {

    init {
        coughRecorder.onMaxAmplitudeUpdate =
            { maxAmplitude -> this@MainViewModel.maxAmplitude.postValue(maxAmplitude.toString()) }
        coughRecorder.amplitudeThreshold = 5000
    }

    var baseDir: String = ""

    var amplitude: String
        get() {
            return coughRecorder.amplitudeThreshold.toString()
        }
        set(value) {
            coughRecorder.amplitudeThreshold = try { value.toInt() } catch (e: NumberFormatException) { null }
        }

    val maxAmplitude: MutableLiveData<String> by lazy {
        MutableLiveData<String>("0")
    }

    override fun onCleared() {
        super.onCleared()
        coughRecorder.stop()
    }

    fun powerClick() {
        if (!coughRecorder.isRecording) {
            val fileName = "sample_${coughDevice.deviceId}_${Date()}"
            coughRecorder.fileName = "${baseDir}/${fileName}"
            coughRecorder.fileExtension = "m4a"
            coughRecorder.start()
        } else {
            coughRecorder.stop()
        }
    }
}
