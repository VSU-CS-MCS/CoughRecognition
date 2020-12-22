package com.coughextractor.recorder

interface CoughRecorder<TRead> {
    var isRecording: Boolean
    var sampleRate: Int
    var fileName: String
    var fileExtension: String
    val filePath
        get() = "$fileName.$fileExtension"
    var onAmplitudesUpdate: (amplitudes: Array<TRead>) -> Unit
    fun start()
    fun stop()
}
