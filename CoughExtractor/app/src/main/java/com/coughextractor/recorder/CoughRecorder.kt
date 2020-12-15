package com.coughextractor.recorder

interface CoughRecorder {
    var isRecording: Boolean
    var sampleRate: Int
    var fileName: String
    var fileExtension: String
    val filePath
        get() = "$fileName.$fileExtension"
    var onMaxAmplitudeUpdate: (maxAmplitude: String) -> Unit
    fun start()
    fun stop()
}
