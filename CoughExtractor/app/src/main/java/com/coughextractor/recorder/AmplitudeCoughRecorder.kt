package com.coughextractor.recorder

import android.media.*
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

import kotlin.collections.ArrayList
import kotlin.experimental.and
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

private const val TAG = "AmplitudeCoughRecorder"

class AmplitudeCoughRecorder @Inject constructor() : CoughRecorder<Short> {

    override var isRecording: Boolean = false

    override var fileName: String = ""
    override var fileExtension: String = ""
    override lateinit var onAmplitudesUpdate: (amplitudes: Array<Short>) -> Unit

    var amplitudeThreshold: Int? = null

    private var audioRecorder: AudioRecord? = null
    private var recordingThread: AudioRecordThread? = null

    private val channelCount = 1
    override var sampleRate: Int = 48000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    private val bytesPerSample = 2 // depends on audioFormat

    private val aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC
    private val outputBitRate = sampleRate * channelCount * bytesPerSample

    /**
     * Amount of amplitudes to read per AudioRecord.read call
     */
    val audioBufferSize: Int = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )

    /**
     * Amount of bytes to read per AudioRecord.read call
     */
    val audioBufferByteSize: Int = audioBufferSize * bytesPerSample

    /**
     * Max amount of Audio.read calls per recording
     */
    val maxRecordReadCalls = 6 * sampleRate / audioBufferSize

    /**
     * Max amount of amplitudes to store per recording
     */
    val maxRecordBufferSize = maxRecordReadCalls * audioBufferSize
    private val audioMimeType = "audio/mp4a-latm"

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

        val audioRecordDataHandlerJob = GlobalScope.launch {
            val handler = AudioRecordDataHandler()
            while (isActive) {
                val (a, b) = audioRecordDataChannel.receive()
                handler.handleData(a, b)
            }
        }
        this.audioRecordDataHandlerJob = audioRecordDataHandlerJob

        isRecording = true
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

        isRecording = false
    }

    private inner class AudioRecordDataHandler() {
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

            val amplitudeThreshold = amplitudeThreshold
            val recordEndOffset = this.recordEndOffset
            if (recordEndOffset == null && amplitudeThreshold != null && maxValue != null && maxValue > amplitudeThreshold) {
                var timeNsEndOffset = timeNsOffset + (maxRecordReadCalls / 2) - 1
                if (timeNsEndOffset >= maxRecordReadCalls) {
                    timeNsEndOffset -= maxRecordReadCalls
                }
                this.recordEndOffset = timeNsEndOffset * audioBufferSize
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

                val shortToByteArray = { it: Short ->
                    val b = ByteArray(2)
                    b[0] = (it and 0x00FF).toByte()
                    b[1] = (it.toInt().shr(8) and 0x000000FF) as Byte
                    b
                }

                for (short in shortBuffer.slice(dataOffsetPart until recordAudioBufferSize)) {
                    byteBuffer.putShort(short.reverseBytes())
                }
                for (short in shortBuffer.slice(0 until dataOffsetPart)) {
                    byteBuffer.putShort(short.reverseBytes())
                }

                GlobalScope.launch {
                    AudioSaver().saveToFile(byteBuffer, timeNsInChronologicalOrder)
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
            val shortBuffer = ShortArray(audioBufferSize)

            try {
                loop@ while (isRecording.get()) {
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

        override fun interrupt() {
            isRecording.set(false)
            super.interrupt()
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
        fun saveToFile(byteBuffer: ByteBuffer, timeNsBuffer: LongArray) {
            val muxer = MediaMuxer(
                "${fileName}_${Date()}.$fileExtension",
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val codec = MediaCodec.createEncoderByType(audioMimeType)
            var outputFormat = MediaFormat.createAudioFormat(
                audioMimeType,
                sampleRate,
                channelCount
            )
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, outputBitRate)
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufferByteSize)
            outputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, audioFormat)

            var totalRecordRead = 0
            var audioTrackIdx = 0
            var isEncoded = false
            var isWritten = false

            val codecWaitingLock = Object()
            val outputBufferChannel =
                Channel<Pair<ByteBuffer, MediaCodec.BufferInfo>>(Channel.BUFFERED)

            val timeUsBuffer = timeNsBuffer.map {
                (it - timeNsBuffer[0]) / 1000
            }

            ByteArrayInputStream(byteBuffer.array(), 0, byteBuffer.position()).use {
                codec.setCallback(object : MediaCodec.Callback() {
                    var isInputEOS = false
                    var isOutputEOS = false

                    override fun onInputBufferAvailable(
                        codec: MediaCodec,
                        inputBufferId: Int
                    ) {
                        if (isInputEOS) {
                            return
                        }

                        val dstBuf: ByteBuffer = codec.getInputBuffer(inputBufferId)!!

                        val tempBuffer = ByteArray(audioBufferByteSize)
                        val bytesRead: Int = it.read(
                            tempBuffer,
                            0,
                            audioBufferByteSize
                        )

                        if (bytesRead == -1) {
                            codec.queueInputBuffer(
                                inputBufferId,
                                0,
                                0,
                                timeNsBuffer[timeNsBuffer.count() - 1] + 1,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isInputEOS = true
                        } else {
                            dstBuf.put(tempBuffer, 0, bytesRead)

                            codec.queueInputBuffer(
                                inputBufferId,
                                0,
                                bytesRead,
                                timeUsBuffer[totalRecordRead],
                                0
                            )
                            totalRecordRead += 1
                        }
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        outputBufferId: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        if (isOutputEOS) {
                            return
                        }

                        val encodedData: ByteBuffer = codec.getOutputBuffer(outputBufferId)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 && info.size != 0) {
                            codec.releaseOutputBuffer(outputBufferId, false)
                        } else {
                            // Handles out of order codec output
                            val outputData = ByteBuffer.allocateDirect(encodedData.capacity())
                            outputData.put(encodedData)
                            outputData.limit(encodedData.limit())
                            outputData.position(encodedData.position())

                            val outputInfo = MediaCodec.BufferInfo()
                            outputInfo.set(
                                info.offset,
                                info.size,
                                info.presentationTimeUs,
                                info.flags
                            )

                            runBlocking {
                                outputBufferChannel.send(Pair(outputData, outputInfo))
                            }

                            codec.releaseOutputBuffer(outputBufferId, false)
                        }

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                            sync()
                        }
                    }

                    override fun onOutputFormatChanged(
                        codec: MediaCodec,
                        format: MediaFormat
                    ) {
                        outputFormat = codec.outputFormat
                        audioTrackIdx = muxer.addTrack(outputFormat)
                        muxer.start()
                        Log.v("AUDIO", "Output format changed - $outputFormat")
                    }

                    override fun onError(
                        codec: MediaCodec,
                        e: MediaCodec.CodecException
                    ) {
                        Log.e("AUDIO", "Codec error ${e.diagnosticInfo}")
                    }

                    fun sync() {
                        synchronized(codecWaitingLock) {
                            isEncoded = isOutputEOS
                            codecWaitingLock.notifyAll()
                        }
                    }
                })
            }
            codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // TODO: Codec output timestep is 21333us
            val muxerWaitingLock = Object()

            val outputJob = GlobalScope.launch {
                val outputDataList: MutableList<Pair<ByteBuffer, MediaCodec.BufferInfo>> =
                    ArrayList()
                val addNewOutputData: (Pair<ByteBuffer, MediaCodec.BufferInfo>) -> (Unit) = {
                    outputDataList.add(it)
                    outputDataList.sortBy { (_, info) -> info.presentationTimeUs }
                }

                while (isActive) {
                    val newOutputData = outputBufferChannel.receive()
                    addNewOutputData(newOutputData)
                    val (_, info) = newOutputData
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }

                for ((encodedData, info) in outputDataList) {
                    muxer.writeSampleData(audioTrackIdx, encodedData, info)
                }

                Log.d("AudioMuxerWriteJob", "Victory")

                synchronized(muxerWaitingLock) {
                    isWritten = true
                    muxerWaitingLock.notifyAll()
                }
            }

            codec.start()

            synchronized(codecWaitingLock) {
                while (!isEncoded) {
                    try {
                        codecWaitingLock.wait()
                    } catch (ie: InterruptedException) {
                    }
                }
            }

            codec.stop()
            codec.release()

            synchronized(muxerWaitingLock) {
                while (!isWritten) {
                    try {
                        muxerWaitingLock.wait()
                    } catch (ie: InterruptedException) {
                    }
                }
                runBlocking {
                    outputJob.cancel()
                    outputJob.join()
                }
            }

            muxer.stop()
            muxer.release()
        }
    }
}
