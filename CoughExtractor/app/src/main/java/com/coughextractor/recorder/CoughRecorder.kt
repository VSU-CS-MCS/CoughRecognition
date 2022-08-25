package com.coughextractor.recorder

interface CoughRecorder<TRead> {
    var token: String
    var isRecording: Boolean
    var sampleRate: Int
    var fileName: String
    val filePath
        get() = "$fileName.wav"
    var onAmplitudesUpdate: (amplitudes: Array<TRead>) -> Unit
    fun start()
    fun stop()
}
