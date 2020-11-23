package com.coughextractor.recorder

import android.media.MediaRecorder
import android.util.Log
import java.io.IOException
import java.util.*
import javax.inject.Inject
import kotlin.concurrent.timer

private const val TAG = "FileCoughRecorder"

class FileCoughRecorder @Inject constructor() : CoughRecorder {

    private var recorder: MediaRecorder? = null

    var filePath: String = ""

    override var isRecording = false

    override fun start() {
        if (isRecording) {
            return;
        }

        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
            setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
            setAudioSamplingRate(48000)

            Log.i(TAG, "file path $filePath")
            setOutputFile(filePath)
            try {
                prepare()
            } catch (e: IOException) {
                Log.e(TAG, "prepare() failed")
            }
            start()
        }

        timer("RecorderTimer", period = 1) {
            if (this@FileCoughRecorder.recorder == null) {
                this.cancel()
            } else {
                TODO("add value to chart data")
                TODO("trigger extract 3 secs before and after")
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
