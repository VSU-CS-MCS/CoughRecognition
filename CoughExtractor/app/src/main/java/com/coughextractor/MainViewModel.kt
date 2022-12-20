package com.coughextractor

import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.coughextractor.recorder.AmplitudeCoughRecorder
import kotlin.math.roundToInt

private const val TAG = "MainViewModel"

class MainViewModel @ViewModelInject constructor(
    val coughRecorder: AmplitudeCoughRecorder
) : ViewModel() {

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
        coughRecorder.soundAmplitudeThreshold = 2000
    }

    var baseDir: String = ""

    var soundAmplitude: String
        get() {
            return coughRecorder.soundAmplitudeThreshold.toString()
        }
        set(value) {
            coughRecorder.soundAmplitudeThreshold = value.toIntOrNull()
            soundAmplitudeObservable.postValue(coughRecorder.soundAmplitudeThreshold)
        }

    var accelerometerAmplitude: String
        get() {
            return coughRecorder.accelerometerAmplitudeThreshold.toString()
        }
        set(value) {
            coughRecorder.accelerometerAmplitudeThreshold = value.toIntOrNull()
            accelerometerAmplitudeObservable.postValue(coughRecorder.accelerometerAmplitudeThreshold)
        }
    val soundAmplitudeObservable: MutableLiveData<Int?> by lazy {
        MutableLiveData<Int?>(coughRecorder.soundAmplitudeThreshold)
    }
    val accelerometerAmplitudeObservable: MutableLiveData<Int?> by lazy {
        MutableLiveData<Int?>(coughRecorder.accelerometerAmplitudeThreshold)
    }

    val amplitudesLock = Object()
    private val amplitudesTimeLengthSec = 6
    private val amplitudesStep = 150
    private val amplitudesPerRead =
        (0 until coughRecorder.audioBufferSize step amplitudesStep).count()
    private val amplitudesPerReadTimeSec =
        coughRecorder.audioBufferSize.toDouble() / coughRecorder.sampleRate.toDouble()
    val amplitudesLength =
        (amplitudesTimeLengthSec * amplitudesPerRead / amplitudesPerReadTimeSec).roundToInt()
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
            coughRecorder.baseDir = baseDir
            coughRecorder.start()
        } else {
            coughRecorder.stop()
        }
        isRecording.postValue(coughRecorder.isRecording)
    }

}
