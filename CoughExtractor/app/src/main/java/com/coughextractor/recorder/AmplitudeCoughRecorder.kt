package com.coughextractor.recorder

import javax.inject.Inject

private const val TAG = "AmplitudeCoughRecorder"

class AmplitudeCoughRecorder @Inject constructor() : CoughRecorder {

    override var isRecording: Boolean = false

    override fun start() {
        if (isRecording) {
            return;
        }
        TODO("Not yet implemented. https://stackoverflow.com/questions/7955041/voice-detection-in-android-application")
        isRecording = true
    }

    override fun stop() {
        if (!isRecording) {
            return
        }
        TODO("Not yet implemented")
        isRecording = false
    }
}
