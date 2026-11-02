package com.samsungnx.camera.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.samsungnx.camera.model.CameraConstants
import com.samsungnx.camera.model.CameraInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class RemoteViewfinderManager(
    private val upnpClient: SamsungUpnpClient,
    private val scope: CoroutineScope
) {
    private var streamJob: Job? = null
    private var isStreaming = false

    var onFrameAvailable: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun startLiveStream(cameraIp: String, discoveredStreamUrl: String) {
        stopLiveStream()
        isStreaming = true

        streamJob = scope.launch(Dispatchers.IO) {
            val candidates = mutableListOf<String>()
            if (discoveredStreamUrl.isNotEmpty()) {
                candidates.add(discoveredStreamUrl)
            }
            candidates.addAll(
                listOf(
                    "http://$cameraIp:7679/liveview",
                    "http://$cameraIp:7679/livestream.avi",
                    "http://$cameraIp:7679/livestream.mp4",
                    "http://$cameraIp:7678/liveview"
                )
            )

            for (urlStr in candidates.distinct()) {
                if (!isActive || !isStreaming) break
                val connected = tryStreamWithHumbleHttp(urlStr)
                if (connected) return@launch
            }

            if (isStreaming) {
                withContext(Dispatchers.Main) {
                    onError?.invoke("Pastikan kamera berada di mode Remote Viewfinder, lalu tekan Hubungkan kembali.")
                }
            }
        }
    }

    /**
     * Menggunakan format HTTP/1.0 mentah sesuai HumbleHttpClient.java pada APK asli Samsung
     * agar web server internal kamera tidak menolak dengan HTTP 400 Bad Request.
     */
    private suspend fun tryStreamWithHumbleHttp(urlStr: String): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            val url = URL(urlStr)
            val host = url.host
            val port = if (url.port != -1) url.port else 7679
            val path = if (url.path.isNotEmpty()) url.path else "/"

            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2500)
            socket.soTimeout = 5000

            val req = "GET $path HTTP/1.1\r\n" +
                    "User-Agent: Lavf/55.19.104\r\n" +
                    "Accept: */*\r\n" +
                    "Range: bytes=0-\r\n" +
                    "Connection: close\r\n" +
                    "Host: $host:$port\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            val bis = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val frameBuffer = ByteArrayOutputStream()
            var inJpeg = false
            var prevByte = -1
            var framesDecoded = 0

            while (isActive && isStreaming) {
                val b = bis.read()
                if (b == -1) break

                if (!inJpeg) {
                    if (prevByte == 0xFF && b == 0xD8) {
                        inJpeg = true
                        frameBuffer.reset()
                        frameBuffer.write(0xFF)
                        frameBuffer.write(0xD8)
                    }
                } else {
                    frameBuffer.write(b)
                    if (prevByte == 0xFF && b == 0xD9) {
                        inJpeg = false
                        val jpegBytes = frameBuffer.toByteArray()
                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bitmap != null) {
                            framesDecoded++
                            withContext(Dispatchers.Main) {
                                onFrameAvailable?.invoke(bitmap)
                            }
                        }
                    }
                }
                prevByte = b
            }
            return@withContext framesDecoded > 0
        } catch (_: Exception) {
            return@withContext false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun stopLiveStream() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
    }

    suspend fun triggerShutter(cam: CameraInfo): Boolean = withContext(Dispatchers.IO) {
        upnpClient.triggerShutter(cam)
    }

    suspend fun touchAutofocus(cam: CameraInfo, normX: Float, normY: Float): Boolean = withContext(Dispatchers.IO) {
        // Samsung NX 3x5 / 3x7 matrix coordinates or pixel coordinates
        val gridX = (normX * 6).toInt().coerceIn(0, 6)
        val gridY = (normY * 2).toInt().coerceIn(0, 2)
        upnpClient.touchAutofocus(cam, gridY, gridX)
    }

    suspend fun zoomIn(cam: CameraInfo): Boolean = withContext(Dispatchers.IO) {
        upnpClient.zoomIn(cam)
    }

    suspend fun zoomOut(cam: CameraInfo): Boolean = withContext(Dispatchers.IO) {
        upnpClient.zoomOut(cam)
    }
}
