package com.coughextractor.recorder

import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

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
    private val bytesPerSample = 2 // depends on audioFormat

    private val aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC
    private val outputBitRate = sampleRate * channelCount * bytesPerSample

    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO

    /**
     * Amount of amplitudes to read per AudioRecord.read call
     */
    val audioBufferSize: Int = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )
    val audioBufferByteSize: Int = audioBufferSize * bytesPerSample
    val audioSamplesCount = 6 * sampleRate / audioBufferSize
    /**
     * Amount of amplitudes to store per recording
     */
    val recordBufferSize = audioSamplesCount * audioBufferSize
    val recordBufferByteSize = audioSamplesCount * audioBufferByteSize
    private val audioMimeType = "audio/mp4a-latm"

    private val audioRecordDataChannel = Channel<Pair<ShortArray, Long>>()
    private var audioRecordDataHandlerJob: Job? = null

    override fun start() {
        if (isRecording) {
            return;
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
            Log.e(TAG, "Audio Record can't initialize!");
            return;
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
            this.recordingThread = null;
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
        val shortBuffer = ShortArray(recordBufferSize)
        var shortBufferOffset = 0
        var recordEndOffset: Int? = null

        fun handleData(data: ShortArray, timeNs: Long) {
            data.copyInto(shortBuffer, shortBufferOffset)

            val readDataIndices = shortBufferOffset until shortBufferOffset+audioBufferSize
            val readData = shortBuffer.slice(readDataIndices)
            val maxValue = readData.maxOrNull()

            val amplitudeThreshold = amplitudeThreshold
            var recordEndOffset = this.recordEndOffset
            if (recordEndOffset == null && amplitudeThreshold != null && maxValue != null && maxValue > amplitudeThreshold) {
                recordEndOffset = shortBufferOffset + recordBufferSize / 2
                if (recordEndOffset > recordBufferSize) {
                    recordEndOffset -= recordBufferSize
                }
                recordEndOffset = recordEndOffset / audioBufferSize * audioBufferSize
            }

            if (recordEndOffset == shortBufferOffset) {
                recordEndOffset = null

                val byteBuffer = ByteBuffer.allocateDirect(recordBufferSize * 2)

                for (short in shortBuffer.slice(shortBufferOffset until recordBufferSize)) {
                    byteBuffer.putShort(short)
                }
                if (shortBufferOffset != 0) {
                    for (short in shortBuffer.slice(0 until shortBufferOffset)) {
                        byteBuffer.putShort(short)
                    }
                }

                GlobalScope.launch {
                    AudioSaver().saveToFile(byteBuffer)
                }
            }

            onAmplitudesUpdate(readData.toTypedArray())

            if (shortBufferOffset + audioBufferSize >= recordBufferSize) {
                shortBufferOffset = 0
            } else {
                shortBufferOffset += audioBufferSize
            }
            this.recordEndOffset = recordEndOffset
        }
    }

    private inner class AudioRecordThread() : Thread() {
        private val isRecording: AtomicBoolean = AtomicBoolean(true)

        override fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val shortBuffer = ShortArray(audioBufferSize)

            try {
                loop@ while (isRecording.get()) {
                    val audioPresentationTimeNs = System.nanoTime();
                    val result: Int = audioRecorder!!.read(
                        shortBuffer,
                        0,
                        audioBufferSize)
                    if (result < 0) {
                        throw RuntimeException("Reading of audio buffer failed: ${getBufferReadFailureReason(result)} $audioBufferSize")
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

        private fun getBufferReadFailureReason(errorCode: Int): String? {
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
            val muxer = MediaMuxer(
                "${fileName}_${Date()}.$fileExtension",
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val codec = MediaCodec.createEncoderByType(audioMimeType)
            var outputFormat = MediaFormat.createAudioFormat(
                audioMimeType,
                sampleRate,
                channelCount,
            )
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, outputBitRate)
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufferByteSize)

            var presentationTimeUs = 0L
            var totalBytesRead = 0L
            var audioTrackIdx = 0
            var isEncoded = false

            val lock = Object()

            ByteArrayInputStream(byteBuffer.array()).use {
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
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isInputEOS = true
                        } else {
                            dstBuf.put(tempBuffer, 0, bytesRead)
                            codec.queueInputBuffer(
                                inputBufferId,
                                0,
                                bytesRead,
                                presentationTimeUs,
                                0
                            )
                            presentationTimeUs += 1000000L * (bytesRead / 2) / sampleRate;
                            totalBytesRead += bytesRead
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
                            muxer.writeSampleData(audioTrackIdx, encodedData, info)
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
                        Log.v("AUDIO", "Output format changed - $outputFormat");
                        audioTrackIdx = muxer.addTrack(outputFormat);
                        muxer.start();
                    }

                    override fun onError(
                        codec: MediaCodec,
                        e: MediaCodec.CodecException
                    ) {
                        Log.e(
                            "AUDIO",
                            "Codec error ${e.diagnosticInfo}"
                        )
                    }

                    fun sync() {
                        synchronized(lock) {
                            isEncoded = isOutputEOS
                            lock.notifyAll()
                        }
                    }
                })
            }
            codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            outputFormat = codec.outputFormat
            codec.start()

            synchronized(lock) {
                while (!isEncoded) {
                    try {
                        lock.wait()
                    } catch (ie: InterruptedException) {
                    }
                }
            }

            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()
        }
    }
}
