package com.coughextractor

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.coughextractor.recorder.AmplitudeCoughRecorder
import com.coughextractor.recorder.Examination
import com.coughextractor.recorder.MyBinder
import kotlin.math.roundToInt

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

        serviceBinder.setonAccelerometryUpdate { accelerometer ->
            synchronized(accelerometerLock) {
                val prevValue = this@MainViewModel.accelerometry.value!!
                val indices = 0 until coughRecorder.audioBufferSize step amplitudesStep

                if (prevValue.count() >= amplitudesLength) {
                    for (index in indices) {
                        prevValue.removeAt(0)
                    }
                }

                for (index in indices) {
                    prevValue.add(accelerometer.toFloat())
                }
                this@MainViewModel.accelerometry.postValue(prevValue)
            }
        }

        serviceBinder.setonAccelerometryXUpdate { accelerometer ->
            synchronized(accelerometerLock) {
                val prevValue = this@MainViewModel.accelerometryX.value!!
                val indices = 0 until coughRecorder.audioBufferSize step amplitudesStep

                if (prevValue.count() >= amplitudesLength) {
                    for (index in indices) {
                        prevValue.removeAt(0)
                    }
                }

                for (index in indices) {
                    prevValue.add(accelerometer.toFloat())
                }
                this@MainViewModel.accelerometryX.postValue(prevValue)
            }
        }

        serviceBinder.setonAccelerometryYUpdate { accelerometer ->
            synchronized(accelerometerLock) {
                val prevValue = this@MainViewModel.accelerometryY.value!!
                val indices = 0 until coughRecorder.audioBufferSize step amplitudesStep

                if (prevValue.count() >= amplitudesLength) {
                    for (index in indices) {
                        prevValue.removeAt(0)
                    }
                }

                for (index in indices) {
                    prevValue.add(accelerometer.toFloat())
                }
                this@MainViewModel.accelerometryY.postValue(prevValue)
            }
        }

        serviceBinder.setBaseDir(baseDir)
        serviceBinder.setToken(token)
        serviceBinder.setExamination(examination)
    }

    fun unbindService() {
        serviceBinder = null
    }

    var baseDir: String = ""
    var token: String = ""
    var examination: Examination = Examination()

    var soundAmplitude: String = "7000"
        get() {
            return serviceBinder?.getSoundAmplitudeThreshold().toString()
        }
        set(value) {
            var v = value.toIntOrNull()
            if (v == null) {
                v = 0
            }
            serviceBinder?.setSoundAmplitudeThreshold(v)
            soundAmplitudeObservable.postValue(v)
            field = v.toString()
        }

    val soundAmplitudeObservable: MutableLiveData<Int?> by lazy {
        MutableLiveData<Int?>(soundAmplitude.toInt())
    }
    var accelerometerAmplitude: String = "35"
        get() {
            return serviceBinder?.getAccelerometerThreshold().toString()
        }
        set(value) {
            var v = value.toIntOrNull()
            if (v == null || v < 0) {
                v = 0
            }
            if (v > 100) {
                v = 100
            }
            serviceBinder?.setAccelerometerThreshold(v)
            field = v.toString()
        }

    val amplitudesLock = Object()
    val accelerometerLock = Object()
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

    val accelerometryX: MutableLiveData<ArrayList<Float>> by lazy {
        MutableLiveData<ArrayList<Float>>(ArrayList(amplitudesLength))
    }

    val accelerometryY: MutableLiveData<ArrayList<Float>> by lazy {
        MutableLiveData<ArrayList<Float>>(ArrayList(amplitudesLength))
    }

    val isRecording: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(false)
    }

    fun profileClick() {
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun powerClick() {
        if (isRecording.value == false) {
            isRecording.postValue(true)
            amplitudes.postValue(ArrayList())
            accelerometry.postValue(ArrayList())
            val intent = Intent(getApplication(), AmplitudeCoughRecorder::class.java)
            ContextCompat.startForegroundService(getApplication(), intent)

        } else {
            isRecording.postValue(false)
            serviceBinder?.getService()?.stopForeground(true)
            serviceBinder?.getService()?.onDestroy()
        }
    }

}
