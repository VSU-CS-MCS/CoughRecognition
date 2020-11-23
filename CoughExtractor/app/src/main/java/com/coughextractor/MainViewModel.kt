package com.coughextractor

import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.ViewModel
import java.util.*

import com.coughextractor.device.CoughDevice
import com.coughextractor.recorder.FileCoughRecorder

class MainViewModel @ViewModelInject constructor(
    val coughRecorder: FileCoughRecorder,
    val coughDevice: CoughDevice)
    : ViewModel() {

    var baseDir: String = ""

    override fun onCleared() {
        super.onCleared()
        coughRecorder.stop()
    }

    fun powerClick() {
        if (!coughRecorder.isRecording) {
            val fileName = "sample_${coughDevice.deviceId}_${Date()}"
            val filePath = "${baseDir}/${fileName}.m4a"
            coughRecorder.filePath = filePath
            coughRecorder.start()
        } else {
            coughRecorder.stop()
        }
    }
}
