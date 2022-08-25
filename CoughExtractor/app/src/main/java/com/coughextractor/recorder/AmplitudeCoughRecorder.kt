package com.coughextractor.recorder


import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.URL
import java.net.URLConnection
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection


data class AuthResponse(
    val token: String,
    val username: String,
    val password: String
)

private const val TAG = "AmplitudeCoughRecorder"

class AmplitudeCoughRecorder @Inject constructor() : CoughRecorder<Byte> {
    override var token: String = ""
    override var isRecording: Boolean = false

    override var fileName: String = ""
    override lateinit var onAmplitudesUpdate: (amplitudes: Array<Byte>) -> Unit

    var amplitudeThreshold: Int? = null

    private var audioRecorder: AudioRecord? = null

    override var sampleRate: Int = 44100
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

        if (audioRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!")
            return
        }

        audioRecorder.startRecording()
        isRecording = true


        writeAudioDataToStorage()

    }

    private fun writeAudioDataToStorage() {
        GlobalScope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfig,
                audioFormat
            )
            val data = ByteArray(bufferSize)
            val file = File(filePath)
            val outputStream = file.outputStream()
            while (isRecording) {
                val operationStatus = audioRecorder?.read(data, 0, bufferSize)
                withContext(Dispatchers.Main) {
                    onAmplitudesUpdate(data.toTypedArray())
                }
                if (AudioRecord.ERROR_INVALID_OPERATION != operationStatus && data.maxOrNull()!! > amplitudeThreshold!!) {
                    GlobalScope.launch(Dispatchers.IO) {
                        outputStream.write(data)
                    }
                }
            }


            outputStream.close()
        }
    }

    override fun stop() {
        if (audioRecorder?.state == AudioRecord.STATE_INITIALIZED) {
            isRecording = false
            audioRecorder!!.stop()
            audioRecorder!!.release()
            writeHeader()
            GlobalScope.launch(Dispatchers.IO) {
                sendToServer()
            }
        }
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
        val apiPost = "https://cough.bfsoft.su/api/files/"

        val multipart = MultipartUtility(apiPost)
        multipart.addFormField("id_examination", "114")
        multipart.addFormField("exam_name", "TEST")
        multipart.addFilePart("file_to", File(filePath))
        multipart.finish()
    }

    private inner class MultipartUtility(requestURL: String?) {
        val httpConn: HttpsURLConnection
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
            if (status == HttpsURLConnection.HTTP_OK) {
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
            httpConn = url.openConnection() as HttpsURLConnection
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
}
