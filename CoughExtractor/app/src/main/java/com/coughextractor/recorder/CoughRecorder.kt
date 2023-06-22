package com.coughextractor.recorder

interface CoughRecorder<TRead> {
    var token: String
    var isRecording: Boolean
    var fileName: String
    val filePath
        get() = "$fileName.wav"
    var onAmplitudesUpdate: (amplitudes: Array<TRead>) -> Unit
    var onAccelerometerUpdate: (amplitudes: TRead) -> Unit
    var onAccelerometerXUpdate: (amplitudes: TRead) -> Unit
    var onAccelerometerYUpdate: (amplitudes: TRead) -> Unit
    var baseDir: String
    var examination: Examination
    var soundAmplitudeThreshold: Int
    var accelerometerThreshold: Int
}
