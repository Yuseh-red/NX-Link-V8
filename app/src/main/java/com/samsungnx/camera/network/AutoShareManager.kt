package com.samsungnx.camera.network

import com.samsungnx.camera.model.CameraConstants
import com.samsungnx.camera.ui.MediaDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class AutoShareManager(
    private val mediaDownloader: MediaDownloader,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var listenJob: Job? = null
    private var isRunning = false

    var onStatusChanged: ((String) -> Unit)? = null
    var onPhotoReceived: ((String, Long) -> Unit)? = null

    suspend fun startAutoShare(cameraIp: String, localIp: String, port: Int = 1801): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            sendS2LInit(cameraIp, localIp, serverSocket?.localPort ?: port)
            return@withContext true
        }

        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            try {
                serverSocket = ServerSocket(0).apply { reuseAddress = true }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    onStatusChanged?.invoke("Gagal membuka port penerima: ${ex.message}")
                }
                return@withContext false
            }
        }

        val boundPort = serverSocket?.localPort ?: port
        isRunning = true

        val initSuccess = sendS2LInit(cameraIp, localIp, boundPort)
        withContext(Dispatchers.Main) {
            if (initSuccess) {
                onStatusChanged?.invoke("AutoShare Terhubung! Siap menerima foto otomatis di port $boundPort & 18100.")
            } else {
                onStatusChanged?.invoke("Siaga mendengarkan kiriman foto di port $boundPort (AutoShare) & 18100 (MobileLink).")
            }
        }

        listenJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleIncomingCameraPhoto(clientSocket)
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }

        true
    }

    private suspend fun sendS2LInit(cameraIp: String, localIp: String, hostPort: Int): Boolean = withContext(Dispatchers.IO) {
        val mac = CameraConstants.SAMSUNG_MAC
        // Port 2000 is the primary Samsung S2L AutoShare init port extracted from classes.dex
        val ports = listOf(801, 802, 803, 7788, 2000, 1801)
        for (port in ports) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cameraIp, port), 1500)
                    socket.soTimeout = 2500

                    val request = "S2L/1.0 Request\r\n" +
                            "Host: SAMSUNG-S2L\r\n" +
                            "Content-Type: text/xml;charset=utf-8\r\n" +
                            "User-Agent: APP-TYPE\r\n" +
                            "Content-Length: 0\r\n" +
                            "HOST-Mac : $mac\r\n" +
                            "HOST-Address : $localIp\r\n" +
                            "HOST-port : $hostPort\r\n" +
                            "HOST-PNumber : none\r\n" +
                            "Host-Gps : none\r\n" +
                            "Access-Method : manual\r\n\r\n"

                    socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()

                    val buffer = ByteArray(1024)
                    val read = socket.getInputStream().read(buffer)
                    if (read > 0) {
                        val resp = String(buffer, 0, read, Charsets.UTF_8)
                        if (resp.contains("Result_OK", ignoreCase = true) || resp.contains("200", ignoreCase = true)) {
                            return@withContext true
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        false
    }

    private suspend fun handleIncomingCameraPhoto(socket: Socket) = withContext(Dispatchers.IO) {
        var input: InputStream? = null
        var output: OutputStream? = null

        try {
            socket.soTimeout = 20000
            input = socket.getInputStream()
            output = socket.getOutputStream()

            val headerStream = ByteArrayOutputStream()
            var prevByte = -1
            var prevPrevByte = -1
            var prev3Byte = -1

            while (true) {
                val b = input.read()
                if (b == -1) break
                headerStream.write(b)
                if (prev3Byte == '\r'.code && prevPrevByte == '\n'.code && prevByte == '\r'.code && b == '\n'.code) {
                    break
                }
                prev3Byte = prevPrevByte
                prevPrevByte = prevByte
                prevByte = b
            }

            val headerText = headerStream.toString("UTF-8")
            if (headerText.contains("ByeBye", ignoreCase = true)) {
                output.write("S2L/1.0 Result_OK\r\nHost: SAMSUNG-S2L\r\nContent-length: 0\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
                return@withContext
            }

            var contentLength = 0L
            val lenMatch = Regex("(?i)Content-length\\s*:\\s*(\\d+)").find(headerText)
            if (lenMatch != null) {
                contentLength = lenMatch.groupValues[1].toLongOrNull() ?: 0L
            }

            var fileName = "SAM_${System.currentTimeMillis()}.JPG"
            for (line in headerText.lines()) {
                if (line.contains(".JPG", ignoreCase = true) || line.contains("FileName", ignoreCase = true)) {
                    val tokens = line.split(" ", ":", "/", "\\")
                    val found = tokens.find { it.endsWith(".JPG", true) || it.endsWith(".JPEG", true) }
                    if (!found.isNullOrEmpty()) {
                        fileName = found.trim()
                    }
                }
            }

            if (contentLength > 0L) {
                val photoStream = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var remaining = contentLength
                while (remaining > 0L) {
                    val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                    val bytesRead = input.read(buffer, 0, toRead)
                    if (bytesRead == -1) break
                    photoStream.write(buffer, 0, bytesRead)
                    remaining -= bytesRead
                }

                val photoBytes = photoStream.toByteArray()
                val saved = mediaDownloader.saveImageBytes(fileName, photoBytes)

                val response = "S2L/1.0 Result_OK\r\nHost: SAMSUNG-S2L\r\nContent-length: 0\r\nAuthorization: none\r\nSub-ErrorCode: 0\r\n\r\n"
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()

                if (saved) {
                    withContext(Dispatchers.Main) {
                        onPhotoReceived?.invoke(fileName, photoBytes.size.toLong())
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    suspend fun stopAutoShare(cameraIp: String) = withContext(Dispatchers.IO) {
        isRunning = false
        listenJob?.cancel()

        for (p in listOf(801, 802, 803, 2000, 1801, 7788)) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cameraIp, p), 800)
                    val bye = "S2L/1.0 ByeBye\r\nHost: SAMSUNG-S2L\r\nContent-Type: text/xml;charset=utf-8\r\nUser-Agent: APP-TYPE\r\nContent-Length: 0\r\nConnection: Close\r\n\r\n"
                    socket.getOutputStream().write(bye.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {}
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        withContext(Dispatchers.Main) {
            onStatusChanged?.invoke("AutoShare Nonaktif")
        }
    }
}
