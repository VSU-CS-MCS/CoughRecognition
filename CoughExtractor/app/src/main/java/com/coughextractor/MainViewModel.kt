package com.coughextractor

import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.roundToInt

import com.coughextractor.device.CoughDevice
import com.coughextractor.recorder.AmplitudeCoughRecorder

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
        coughRecorder.amplitudeThreshold = 5000
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
    private val amplitudesStep = 150
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
            val fileName = "sample_${coughDevice.deviceId}_${Date()}"
            coughRecorder.fileName = "${baseDir}/${fileName}"
            coughRecorder.fileExtension = "m4a"
            coughRecorder.start()
        } else {
            coughRecorder.stop()
        }
        isRecording.postValue(coughRecorder.isRecording)
    }
}
