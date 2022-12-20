package com.coughextractor.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "AmplitudeCoughRecorder"


class AmplitudeCoughRecorder @Inject constructor() : CoughRecorder<Short> {

    lateinit var examination: Examination
    override var isRecording: Boolean = false
    override var fileName: String = ""

    override lateinit var onAmplitudesUpdate: (amplitudes: Array<Short>) -> Unit
    var soundAmplitudeThreshold: Int? = null

    var accelerometerAmplitudeThreshold: Int? = null

    private var audioRecorder: AudioRecord? = null
    private var recordingThread: AudioRecordThread? = null
    var baseDir = ""
    private val bytesPerSample = 2 // depends on audioFormat

    override var token: String = ""

    override var sampleRate: Int = 48000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    private fun bitPerSample(audioEncoding: Int) = when (audioEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        else -> 16
    }

    /**
     * Amount of amplitudes to read per AudioRecord.read call
     */
    val audioBufferSize: Int = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )

    var accelerometerThread: AccelerometerThread? = null

    /**
     * Max amount of Audio.read calls per recording
     */
    val maxRecordReadCalls = 6 * sampleRate / audioBufferSize

    /**
     * Max amount of amplitudes to store per recording
     */
    val maxRecordBufferSize = maxRecordReadCalls * audioBufferSize

    private val audioRecordDataChannel = Channel<Pair<ShortArray, Long>>(Channel.BUFFERED)
    private var audioRecordDataHandlerJob: Job? = null

    override fun start() {
        if (isRecording) {
            return
        }

        val audioRecorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            audioBufferSize
        )
        this.audioRecorder = audioRecorder

        if (AutomaticGainControl.isAvailable()) {
            val agc = AutomaticGainControl.create(audioRecorder.audioSessionId)
            Log.d(TAG, "AGC is " + if (agc.enabled) "enabled" else "disabled")
            if (!agc.enabled) {
                agc.enabled = true
                Log.d(
                    TAG,
                    "AGC is " + if (agc.enabled) "enabled" else "disabled" + " after trying to enable"
                )
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            val suppressor = NoiseSuppressor.create(audioRecorder.audioSessionId)
            Log.d(TAG, "NS is " + if (suppressor.enabled) "enabled" else "disabled")
            if (!suppressor.enabled) {
                suppressor.enabled = true
                Log.d(
                    TAG,
                    "NS is " + if (suppressor.enabled) "enabled" else "disabled" + " after trying to disable"
                )
            }
        }

        if (AcousticEchoCanceler.isAvailable()) {
            val aec = AcousticEchoCanceler.create(audioRecorder.audioSessionId)
            Log.d(TAG, "AEC is " + if (aec.enabled) "enabled" else "disabled")
            if (!aec.enabled) {
                aec.enabled = true
                Log.d(
                    TAG,
                    "AEC is " + if (aec.enabled) "enabled" else "disabled" + " after trying to disable"
                )
            }
        }

        if (audioRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!")
            return
        }

        audioRecorder.startRecording()

        val recordingThread = AudioRecordThread()
        this.recordingThread = recordingThread
        recordingThread.start()

        val accelerometerThread = AccelerometerThread()
        accelerometerThread.priority = 10
        this.accelerometerThread = accelerometerThread
        accelerometerThread.start()

        try {
            val audioRecordDataHandlerJob = GlobalScope.launch {
                val handler = AudioRecordDataHandler()

                while (isActive) {
                    val (a, b) = audioRecordDataChannel.receive()
                    handler.handleData(a, b)
                }
            }
            this.audioRecordDataHandlerJob = audioRecordDataHandlerJob
            isRecording = true
        } catch (e: Exception) {
            //
        }


    }

    override fun stop() {
        if (!isRecording) {
            return
        }

        val audioRecordDataHandlerJob = this.audioRecordDataHandlerJob
        if (audioRecordDataHandlerJob != null) {
            runBlocking {
                audioRecordDataHandlerJob.cancel()
                audioRecordDataHandlerJob.join()
            }
            this.audioRecordDataHandlerJob = null
        }

        val recordingThread = this.recordingThread
        if (recordingThread != null) {
            recordingThread.interrupt()
            this.recordingThread = null
        }

        val recorder = this.audioRecorder
        if (recorder != null) {
            recorder.stop()
            recorder.release()
            this.audioRecorder = null
        }

        val accelerometerThread = accelerometerThread
        if (accelerometerThread != null) {
            accelerometerThread.interrupt()
            this.accelerometerThread = null
        }

        isRecording = false
    }

    private inner class AudioRecordDataHandler {
        val shortBuffer = ShortArray(maxRecordBufferSize)
        val timeNsBuffer = LongArray(maxRecordBufferSize / audioBufferSize)
        var shortBufferOffset = 0
        val timeNsOffset
            get() = shortBufferOffset / audioBufferSize
        var recordEndOffset: Int? = null

        fun Short.reverseBytes(): Short {
            val v0 = ((this.toInt() ushr 0) and 0xFF)
            val v1 = ((this.toInt() ushr 8) and 0xFF)
            return ((v1 and 0xFF) or (v0 shl 8)).toShort()
        }

        fun handleData(data: ShortArray, timeNs: Long) {
            data.copyInto(shortBuffer, shortBufferOffset)
            timeNsBuffer[timeNsOffset] = timeNs

            val readDataIndices = shortBufferOffset until shortBufferOffset + audioBufferSize
            val readData = shortBuffer.slice(readDataIndices)
            val maxValue = readData.maxOrNull()

            val amplitudeThreshold = soundAmplitudeThreshold
            val recordEndOffset = this.recordEndOffset
            if (recordEndOffset == null && amplitudeThreshold != null && maxValue != null && maxValue > amplitudeThreshold) {
                if (accelerometerThread != null) {
                    if (accelerometerThread!!.isCough.get()) {
                        var timeNsEndOffset = timeNsOffset + (maxRecordReadCalls / 2) - 1
                        if (timeNsEndOffset >= maxRecordReadCalls) {
                            timeNsEndOffset -= maxRecordReadCalls
                        }
                        this.recordEndOffset = timeNsEndOffset * audioBufferSize
                        Log.e(TAG, "Cough Registered")
                        Log.e(TAG, accelerometerThread!!.currentAccelerometer.toString())
                    }
                } else {
                    var timeNsEndOffset = timeNsOffset + (maxRecordReadCalls / 2) - 1
                    if (timeNsEndOffset >= maxRecordReadCalls) {
                        timeNsEndOffset -= maxRecordReadCalls
                    }
                    this.recordEndOffset = timeNsEndOffset * audioBufferSize
                }
            } else if (recordEndOffset == shortBufferOffset) {
                this.recordEndOffset = null

                // In case recording has just started we store a lesser recording
                val firstZeroValueIndex = timeNsBuffer.indexOfFirst { it == 0L }
                val recordAudioSamplesCount: Int
                val recordAudioBufferSize: Int

                if (firstZeroValueIndex == -1) {
                    recordAudioSamplesCount = maxRecordReadCalls
                    recordAudioBufferSize = maxRecordBufferSize
                } else {
                    recordAudioSamplesCount = firstZeroValueIndex
                    recordAudioBufferSize = firstZeroValueIndex * audioBufferSize
                }

                val timeNsInChronologicalOrder = LongArray(recordAudioSamplesCount)
                val byteBuffer = ByteBuffer.allocateDirect(recordAudioBufferSize * bytesPerSample)

                val timeNsOffsetPart = timeNsOffset + 1
                val dataOffsetPart = timeNsOffsetPart * audioBufferSize

                timeNsBuffer.copyInto(
                    timeNsInChronologicalOrder,
                    0,
                    timeNsOffsetPart,
                    recordAudioSamplesCount
                )
                timeNsBuffer.copyInto(
                    timeNsInChronologicalOrder,
                    recordAudioSamplesCount - timeNsOffsetPart,
                    0,
                    timeNsOffsetPart
                )

                for (short in shortBuffer.slice(dataOffsetPart until recordAudioBufferSize)) {
                    byteBuffer.putShort(short.reverseBytes())
                }
                for (short in shortBuffer.slice(0 until dataOffsetPart)) {
                    byteBuffer.putShort(short.reverseBytes())
                }

                GlobalScope.launch {
                    AudioSaver().saveToFile(byteBuffer)
                }
            }

            onAmplitudesUpdate(readData.toTypedArray())

            if (shortBufferOffset + audioBufferSize >= maxRecordBufferSize) {
                shortBufferOffset = 0
            } else {
                shortBufferOffset += audioBufferSize
            }
        }
    }

    private inner class AudioRecordThread() : Thread() {
        private val isRecording: AtomicBoolean = AtomicBoolean(true)

        override fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            try {
                loop@ while (isRecording.get()) {
                    val shortBuffer = ShortArray(audioBufferSize)
                    val audioPresentationTimeNs = System.nanoTime()
                    val result: Int = audioRecorder!!.read(
                        shortBuffer,
                        0,
                        audioBufferSize
                    )
                    if (result < 0) {
                        throw RuntimeException(
                            "Reading of audio buffer failed: ${
                                getBufferReadFailureReason(
                                    result
                                )
                            } $audioBufferSize"
                        )
                    }

                    try {
                        runBlocking {
                            audioRecordDataChannel.send(Pair(shortBuffer, audioPresentationTimeNs))
                        }
                    } catch (exception: InterruptedException) {
                        break@loop
                    }
                }
            } catch (e: IOException) {
                throw RuntimeException("Writing of recorded audio failed", e)
            }
        }

        private fun getBufferReadFailureReason(errorCode: Int): String {
            return when (errorCode) {
                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                AudioRecord.ERROR -> "ERROR"
                else -> "Unknown ($errorCode)"
            }
        }
    }

    private inner class AudioSaver {
        fun saveToFile(byteBuffer: ByteBuffer) {
            val localDate = LocalDateTime.now().atZone(ZoneId.of("UTC"))
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
            val fname = "sample_${localDate.format(formatter)}"
            fileName = "${baseDir}/${fname}"
            val file = File(filePath)
            val outputStream = file.outputStream()
            outputStream.write(byteBuffer.array())
            outputStream.close()

            this.writeHeader()
            this.sendToServer()
        }

        private fun writeHeader() {
            val inputStream = File(filePath).inputStream()
            val totalAudioLen = inputStream.channel.size() - 44
            val totalDataLen = totalAudioLen + 36
            val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO)
                1
            else
                2

            val sampleRate = sampleRate.toLong()
            val byteRate =
                (bitPerSample(audioFormat) * sampleRate * channels / 8).toLong()
            val header = getWavFileHeaderByteArray(
                totalAudioLen,
                totalDataLen,
                sampleRate,
                channels,
                byteRate,
                bitPerSample(audioFormat)
            )

            val randomAccessFile = RandomAccessFile(File(filePath), "rw")
            randomAccessFile.seek(0)
            randomAccessFile.write(header)
            randomAccessFile.close()
        }

        private fun getWavFileHeaderByteArray(
            totalAudioLen: Long, totalDataLen: Long, longSampleRate: Long,
            channels: Int, byteRate: Long, bitsPerSample: Int
        ): ByteArray {
            val header = ByteArray(44)
            header[0] = 'R'.toByte()
            header[1] = 'I'.toByte()
            header[2] = 'F'.toByte()
            header[3] = 'F'.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = (totalDataLen shr 8 and 0xff).toByte()
            header[6] = (totalDataLen shr 16 and 0xff).toByte()
            header[7] = (totalDataLen shr 24 and 0xff).toByte()
            header[8] = 'W'.toByte()
            header[9] = 'A'.toByte()
            header[10] = 'V'.toByte()
            header[11] = 'E'.toByte()
            header[12] = 'f'.toByte()
            header[13] = 'm'.toByte()
            header[14] = 't'.toByte()
            header[15] = ' '.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (longSampleRate and 0xff).toByte()
            header[25] = (longSampleRate shr 8 and 0xff).toByte()
            header[26] = (longSampleRate shr 16 and 0xff).toByte()
            header[27] = (longSampleRate shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = (byteRate shr 8 and 0xff).toByte()
            header[30] = (byteRate shr 16 and 0xff).toByte()
            header[31] = (byteRate shr 24 and 0xff).toByte()
            header[32] = (channels * (bitsPerSample / 8)).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            header[36] = 'd'.toByte()
            header[37] = 'a'.toByte()
            header[38] = 't'.toByte()
            header[39] = 'a'.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = (totalAudioLen shr 8 and 0xff).toByte()
            header[42] = (totalAudioLen shr 16 and 0xff).toByte()
            header[43] = (totalAudioLen shr 24 and 0xff).toByte()
            return header
        }

        private fun sendToServer() {
            val apiPost = "http://cough.bfsoft.su/api/files/"

            val multipart = MultipartUtility(apiPost, token)
            multipart.addFormField("id_examination", examination.id.toString())
            multipart.addFormField("exam_name", examination.examination_name)
            multipart.addFilePart("file_to", File(filePath))
            multipart.finish()
            Files.deleteIfExists(Paths.get(filePath))
        }


    }
}
