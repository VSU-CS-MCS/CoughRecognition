package com.coughextractor.recorder

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.ArrayList

class MultipartUtility(requestURL: String?, token: String) {
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
