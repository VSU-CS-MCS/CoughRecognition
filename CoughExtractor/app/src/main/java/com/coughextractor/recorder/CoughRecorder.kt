package com.coughextractor.recorder

interface CoughRecorder {
    var isRecording: Boolean
    fun start()
    fun stop()
}
