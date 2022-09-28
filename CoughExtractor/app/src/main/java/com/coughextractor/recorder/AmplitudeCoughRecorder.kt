package com.coughextractor.recorder

import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import kotlin.experimental.and

data class AuthResponse(
    val token: String,
    val username: String,
    val password: String
)

private const val TAG = "AmplitudeCoughRecorder"

class AmplitudeCoughRecorder @Inject constructor() : CoughRecorder<Short> {

    var filesCount: Int = 0
    override var isRecording: Boolean = false

    override var fileName: String = ""
    override lateinit var onAmplitudesUpdate: (amplitudes: Array<Short>) -> Unit

    var amplitudeThreshold: Int? = null

    private var audioRecorder: AudioRecord? = null
    private var recordingThread: AudioRecordThread? = null

    private val channelCount = 1
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


    private inner class MultipartUtility(requestURL: String?) {
        val httpConn: HttpURLConnection
        private val lineFeed = "\r\n"
        private val boundary: String = "***" + System.currentTimeMillis() + "***"
        private val outputStream: OutputStream
        private val writer: PrintWriter

        /**
         * Adds a form field to the request
         *
         * @param name  field name
         * @param value field value
         */
        fun addFormField(name: String, value: String?) {
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"$name\"")
                .append(lineFeed)
            writer.append("Content-Type: text/plain;").append(
                lineFeed
            )
            writer.append(lineFeed)
            writer.append(value)
            writer.flush()
            writer.append(lineFeed)
        }

        /**
         * Adds a upload file section to the request
         *
         * @param fieldName  name attribute in <input type="file" name="..."></input>
         * @param uploadFile a File to be uploaded
         * @throws IOException
         */
        @Throws(IOException::class)
        fun addFilePart(fieldName: String, uploadFile: File) {
            val fileName = uploadFile.name
            writer.append("--$boundary").append(lineFeed)
            writer.append(
                "Content-Disposition: form-data; name=\"" + fieldName
                        + "\"; filename=\"" + fileName + "\""
            )
                .append(lineFeed)
            writer.append(
                (
                        "Content-Type: "
                                + URLConnection.guessContentTypeFromName(fileName))
            )
                .append(lineFeed)
            writer.append("Content-Transfer-Encoding: binary").append(lineFeed)
            writer.append(lineFeed)
            writer.flush()
            val inputStream = FileInputStream(uploadFile)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            inputStream.close()
            writer.append(lineFeed)
            writer.flush()
        }

        /**
         * Completes the request and receives response from the server.
         *
         * @return a list of Strings as response in case the server returned
         * status OK, otherwise an exception is thrown.
         * @throws IOException
         */
        @Throws(IOException::class)
        fun finish(): List<String?> {
            val response: MutableList<String?> = ArrayList()
            writer.append(lineFeed).flush()
            writer.append("--$boundary--").append(lineFeed)
            writer.close()

            // checks server's status code first
            val status: Int = httpConn.responseCode
            if (status == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(
                    InputStreamReader(
                        httpConn.inputStream
                    )
                )
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    response.add(line)
                }
                reader.close()
                httpConn.disconnect()
            } else {
                throw IOException("Server returned non-OK status: $status")
            }
            return response
        }

        /**
         * This constructor initializes a new HTTP POST request with content type
         * is set to multipart/form-data
         *
         * @param requestURL
         * @param charset
         * @throws IOException
         */
        init {
            // creates a unique boundary based on time stamp
            val url = URL(requestURL)
            httpConn = url.openConnection() as HttpURLConnection
            httpConn.useCaches = false
            httpConn.doOutput = true // indicates POST method
            httpConn.doInput = true
            httpConn.requestMethod = "POST"
            httpConn.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
            httpConn.setRequestProperty("Authorization", "token $token")
            outputStream = httpConn.outputStream
            writer = PrintWriter(
                OutputStreamWriter(outputStream),
                true
            )
        }
    }

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

            val multipart = MultipartUtility(apiPost)
            multipart.addFormField("id_examination", "114")
            multipart.addFormField("exam_name", "TEST")
            multipart.addFilePart("file_to", File(filePath))
            multipart.finish()
        }


    }
}
