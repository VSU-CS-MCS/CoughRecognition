package com.coughextractor.recorder

import android.media.*
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "AmplitudeCoughRecorder"

class AmplitudeCoughRecorder @Inject constructor() : CoughRecorder {

    override var isRecording: Boolean = false

    override var fileName: String = ""
    override var fileExtension: String = ""
    override lateinit var onMaxAmplitudeUpdate: (maxAmplitude: String) -> Unit

    var amplitudeThreshold: Int? = null

    private var audioRecorder: AudioRecord? = null
    private var recordingThread: ReadThread? = null

    private val channelCount = 1
    override var sampleRate: Int = 48000
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2 // depends on audioFormat

    private val bitRate = sampleRate * channelCount * bytesPerSample

    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO

    val audioBufferSize: Int = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )
    val recordBufferSize = 6 * sampleRate / audioBufferSize * audioBufferSize
    private val audioMimeType = "audio/mp4a-latm"

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

        if (audioRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!");
            return;
        }

        audioRecorder.startRecording()

        val recordingThread = ReadThread()
        recordingThread.start()

        this.audioRecorder = audioRecorder
        this.recordingThread = recordingThread
        isRecording = true
    }

    override fun stop() {
        if (!isRecording) {
            return
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

    private inner class ReadThread() : Thread() {
        private val isRecording: AtomicBoolean = AtomicBoolean(true)

        override fun run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            val shortBuffer = ShortArray(recordBufferSize)
            var shortBufferOffset = 0
            var recordEndOffset: Int? = null

            try {
                while (isRecording.get()) {
                    val result: Int = audioRecorder!!.read(
                        shortBuffer,
                        shortBufferOffset,
                        audioBufferSize
                    )
                    if (result < 0) {
                        throw RuntimeException(
                            "Reading of audio buffer failed: ${getBufferReadFailureReason(result)} $shortBufferOffset $audioBufferSize"
                        )
                    }

                    val readDataIndices = shortBufferOffset until shortBufferOffset+audioBufferSize
                    val readData = shortBuffer.slice(readDataIndices)
                    val maxValue = readData.maxOrNull()

                    val amplitudeThreshold = amplitudeThreshold
                    if (amplitudeThreshold != null && maxValue != null && maxValue > amplitudeThreshold) {
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

                        saveToFile(byteBuffer)
                    }

                    onMaxAmplitudeUpdate(maxValue.toString())

                    if (shortBufferOffset + audioBufferSize >= recordBufferSize) {
                        shortBufferOffset = 0
                    } else {
                        shortBufferOffset += audioBufferSize
                    }
                }
            } catch (e: IOException) {
                throw RuntimeException("Writing of recorded audio failed", e)
            }
        }

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
            outputFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectHE
            )
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)

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
                        val tempBuffer = ByteArray(dstBuf.capacity())
                        val bytesRead: Int = it.read(tempBuffer, 0, dstBuf.limit())

                        if (bytesRead == -1) {
                            codec.queueInputBuffer(inputBufferId, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            totalBytesRead += bytesRead
                            dstBuf.put(tempBuffer, 0, bytesRead);
                            codec.queueInputBuffer(
                                inputBufferId,
                                0,
                                bytesRead,
                                presentationTimeUs,
                                0
                            )
                            presentationTimeUs += 1000000L * (bytesRead / 2) / sampleRate;
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
}
