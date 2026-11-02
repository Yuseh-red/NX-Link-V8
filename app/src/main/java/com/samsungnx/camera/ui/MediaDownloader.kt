package com.samsungnx.camera.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.samsungnx.camera.model.CameraConstants
import com.samsungnx.camera.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class MediaDownloader(private val context: Context) {

    suspend fun downloadItem(
        item: MediaItem,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var uri: Uri? = null

        try {
            val url = URL(item.url)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", CameraConstants.USER_AGENT_RVF_ML)
                setRequestProperty("Access-Method", "manual")
            }

            if (conn.responseCode != 200) {
                return@withContext false
            }

            val headerLen = conn.contentLength.toLong()
            val totalBytes: Long = if (headerLen > 0L) headerLen else item.fileSize

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.title)
                put(MediaStore.MediaColumns.MIME_TYPE, if (item.title.endsWith(".mp4", ignoreCase = true)) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/SamsungNX")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (item.title.endsWith(".mp4", ignoreCase = true)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }

            uri = context.contentResolver.insert(collection, values) ?: return@withContext false
            outputStream = context.contentResolver.openOutputStream(uri) ?: return@withContext false
            inputStream = conn.inputStream

            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            var count = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                count += bytesRead
                if (totalBytes > 0L) {
                    val progress = ((count * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            }
            outputStream.flush()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
            }
            true
        } catch (_: Exception) {
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            false
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun saveImageBytes(fileName: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        var uri: Uri? = null
        try {
            val isVideo = fileName.endsWith(".mp4", ignoreCase = true)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/SamsungNX")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }

            uri = context.contentResolver.insert(collection, values) ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(data)
                os.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) {
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            false
        }
    }
}
