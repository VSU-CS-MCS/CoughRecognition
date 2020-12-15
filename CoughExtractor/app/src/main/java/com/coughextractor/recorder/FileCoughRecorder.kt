package com.coughextractor.recorder

import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import java.io.IOException
import java.util.*
import javax.inject.Inject
import kotlin.concurrent.timer
import kotlin.properties.Delegates

private const val TAG = "FileCoughRecorder"

class FileCoughRecorder @Inject constructor() : CoughRecorder {

    private var recorder: MediaRecorder? = null

    override var isRecording = false
    override var sampleRate: Int = 48000
    override var fileName: String = ""
    override var fileExtension: String = ""
    override lateinit var onMaxAmplitudeUpdate: (maxAmplitude: String) -> Unit

    override fun start() {
        if (isRecording) {
            return;
        }

        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
            setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
            setAudioSamplingRate(sampleRate)

            Log.i(TAG, "file path $filePath")
            setOutputFile(filePath)
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(TAG, "prepare() failed")
            }
            start()
        }

        timer("RecorderTimer", period = 5000) {
            val recorder = this@FileCoughRecorder.recorder
            if (recorder == null) {
                this.cancel()
            } else {
                onMaxAmplitudeUpdate(recorder.maxAmplitude.toString())
            }
        }

        this.recorder = recorder
        isRecording = true;
    }

    override fun stop() {
        val recorder = recorder ?: return
        recorder.apply {
            stop()
            reset()
            release()
        }
        this.recorder = null
        isRecording = false
    }
}
