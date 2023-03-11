package com.coughextractor

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.coughextractor.recorder.AmplitudeCoughRecorder
import com.coughextractor.recorder.Examination
import com.coughextractor.recorder.MyBinder
import kotlin.math.roundToInt

private const val TAG = "MainViewModel"

class MainViewModel @ViewModelInject constructor(
    application: Application,
    val coughRecorder: AmplitudeCoughRecorder
) : AndroidViewModel(application) {

    var serviceBinder: MyBinder? = null

    fun bindService(serviceBinder: MyBinder) {
        this.serviceBinder = serviceBinder
        serviceBinder.setonAmplitudesUpdate { amplitudes ->
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

        serviceBinder.setonAccelerometryUpdate { accelerometry ->
            synchronized(accLock) {
                val prevValue = this@MainViewModel.accelerometry.value!!
                val indices = 0 until coughRecorder.audioBufferSize step amplitudesStep

                if (prevValue.count() >= amplitudesLength) {
                    for (index in indices) {
                        prevValue.removeAt(0)
                    }
                }

                for (index in indices) {
                    prevValue.add(accelerometry.toFloat())
                }
                this@MainViewModel.accelerometry.postValue(prevValue)
            }
        }

        serviceBinder.setBaseDir(baseDir)
        serviceBinder.setToken(token)
        serviceBinder.setExamination(examination)
    }

    fun unbindService() {
        serviceBinder = null
    }

    private fun init() {
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

        coughRecorder.onAccelerometryUpdate = { accelerometry ->
            synchronized(accLock) {
                val prevValue = this@MainViewModel.accelerometry.value!!
                val indices = 0 until coughRecorder.audioBufferSize step amplitudesStep

                if (prevValue.count() >= amplitudesLength) {
                    for (index in indices) {
                        prevValue.removeAt(0)
                    }
                }

                for (index in indices) {
                    prevValue.add(accelerometry.toFloat())
                }
                this@MainViewModel.accelerometry.postValue(prevValue)
            }
        }
        coughRecorder.accelerometerAmplitudeThreshold = 500
    }

    var baseDir: String = ""
    var token: String = ""
    lateinit var examination: Examination

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
    val accLock = Object()
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

    val accelerometry: MutableLiveData<ArrayList<Float>> by lazy {
        MutableLiveData<ArrayList<Float>>(ArrayList(amplitudesLength))
    }

    val isRecording: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    override fun onCleared() {
        super.onCleared()
        coughRecorder.stopForeground(true)
    }

    fun powerClick() {
        if (!coughRecorder.isRecording) {
            amplitudes.postValue(ArrayList())
            accelerometry.postValue(ArrayList())
            val intent = Intent(getApplication(), AmplitudeCoughRecorder::class.java)
            ContextCompat.startForegroundService(getApplication(), intent)

        } else {
            coughRecorder.stopRecording()
        }
        isRecording.postValue(coughRecorder.isRecording)
    }

}
