package com.samsungnx.camera.network

import android.util.Log
import com.samsungnx.camera.model.CameraConstants
import com.samsungnx.camera.model.CameraInfo
import com.samsungnx.camera.model.CameraSettings
import com.samsungnx.camera.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class SamsungUpnpClient {

    companion object {
        const val TAG = "SamsungUpnpClient"
        const val USER_AGENT = CameraConstants.USER_AGENT_RVF_ML
    }

    suspend fun discoverCamera(cameraIp: String, hintUrl: String = ""): CameraInfo = withContext(Dispatchers.IO) {
        val candidateXmlUrls = mutableListOf<String>()
        if (hintUrl.startsWith("http")) {
            candidateXmlUrls.add(hintUrl)
        }

        // Try SSDP M-SEARCH to get exact LOCATION header
        val ssdpLoc = performSsdpSearch(cameraIp)
        if (ssdpLoc.startsWith("http")) {
            candidateXmlUrls.add(ssdpLoc)
        }

        candidateXmlUrls.addAll(
            listOf(
                "http://$cameraIp:7679/smp_2_",
                "http://$cameraIp:7679/smp_1_",
                "http://$cameraIp:7679/rootDesc.xml",
                "http://$cameraIp:7678/smp_2_"
            )
        )

        for (xmlUrl in candidateXmlUrls.distinct()) {
            try {
                val url = URL(xmlUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 3000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Access-Method", "manual")
                }

                if (conn.responseCode == 200) {
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    var modelName = "Samsung NX3000"
                    var contentDirUrl = ""
                    var deviceControlUrl = ""
                    var deviceControlUrn = "urn:schemas-upnp-org:service:ContentDirectory:1"

                    val factory = XmlPullParserFactory.newInstance()
                    val parser = factory.newPullParser()
                    parser.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")

                    var eventType = parser.eventType
                    var currentServiceType = ""

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tag = parser.name
                        if (eventType == XmlPullParser.START_TAG) {
                            if (tag.equals("modelName", ignoreCase = true)) {
                                val m = parser.nextText().trim()
                                if (m.isNotEmpty()) modelName = m
                            } else if (tag.equals("serviceType", ignoreCase = true)) {
                                currentServiceType = parser.nextText().trim()
                            } else if (tag.equals("controlURL", ignoreCase = true)) {
                                val ctrl = parser.nextText().trim()
                                val port = if (url.port != -1) url.port else 7679
                                val slash = if (ctrl.startsWith("/")) "" else "/"
                                val fullCtrl = if (ctrl.startsWith("http")) ctrl else "http://${url.host}:$port$slash$ctrl"

                                if (currentServiceType.contains("ContentDirectory", ignoreCase = true)) {
                                    contentDirUrl = fullCtrl
                                    if (deviceControlUrl.isEmpty()) {
                                        deviceControlUrl = fullCtrl
                                        deviceControlUrn = currentServiceType
                                    }
                                } else if (!currentServiceType.contains("ConnectionManager", ignoreCase = true) && currentServiceType.isNotEmpty()) {
                                    deviceControlUrl = fullCtrl
                                    deviceControlUrn = currentServiceType
                                }
                            }
                        }
                        eventType = parser.next()
                    }

                    if (contentDirUrl.isEmpty()) contentDirUrl = "http://$cameraIp:7679/smp_4_"
                    if (deviceControlUrl.isEmpty()) deviceControlUrl = contentDirUrl

                    return@withContext CameraInfo(
                        ip = cameraIp,
                        modelName = modelName,
                        contentDirUrl = contentDirUrl,
                        deviceControlUrl = deviceControlUrl,
                        deviceControlUrn = deviceControlUrn
                    )
                }
            } catch (_: Exception) {}
        }

        CameraInfo(
            ip = cameraIp,
            modelName = "Samsung NX3000",
            contentDirUrl = "http://$cameraIp:7679/smp_4_",
            deviceControlUrl = "http://$cameraIp:7679/smp_4_",
            deviceControlUrn = "urn:schemas-upnp-org:service:ContentDirectory:1"
        )
    }

    private fun performSsdpSearch(cameraIp: String): String {
        try {
            val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n" +
                    "User-Agent: $USER_AGENT\r\n\r\n"
            val sendData = msearch.toByteArray(Charsets.UTF_8)

            DatagramSocket().use { udpSocket ->
                udpSocket.soTimeout = 1800
                try {
                    udpSocket.send(DatagramPacket(sendData, sendData.size, InetAddress.getByName("239.255.255.250"), 1900))
                } catch (_: Exception) {}
                try {
                    udpSocket.send(DatagramPacket(sendData, sendData.size, InetAddress.getByName(cameraIp), 1900))
                } catch (_: Exception) {}

                val recvBuf = ByteArray(2048)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 1800) {
                    val pkt = DatagramPacket(recvBuf, recvBuf.size)
                    udpSocket.receive(pkt)
                    val resp = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    for (line in resp.lines()) {
                        if (line.trim().startsWith("LOCATION:", ignoreCase = true)) {
                            return line.substringAfter(":").trim()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    suspend fun browseMedia(cameraIp: String, hintControlUrl: String = ""): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val candidateUrls = listOf(
            hintControlUrl,
            "http://$cameraIp:7679/smp_4_",
            "http://$cameraIp:7679/smp_5_",
            "http://$cameraIp:7679/ContentDirectory/control",
            "http://$cameraIp:7679/upnp/control/ContentDirectory1"
        ).filter { it.isNotEmpty() }.distinct()

        for (controlUrl in candidateUrls) {
            val subContainers = mutableListOf<String>()
            browseSingleObject(controlUrl, "0", items, subContainers)

            val visited = mutableSetOf("0")
            val queue = ArrayDeque(subContainers)
            while (queue.isNotEmpty() && visited.size < 15) {
                val nextId = queue.removeFirst()
                if (visited.add(nextId)) {
                    val childContainers = mutableListOf<String>()
                    browseSingleObject(controlUrl, nextId, items, childContainers)
                    for (cid in childContainers) {
                        if (cid !in visited) queue.addLast(cid)
                    }
                }
            }

            if (items.isEmpty()) {
                for (fallbackId in listOf("1", "2", "3", "0/0", "0/1")) {
                    if (visited.add(fallbackId)) {
                        browseSingleObject(controlUrl, fallbackId, items, mutableListOf())
                    }
                    if (items.isNotEmpty()) break
                }
            }

            if (items.isNotEmpty()) break
        }
        items.distinctBy { it.url }
    }

    private fun browseSingleObject(
        controlUrl: String,
        objectId: String,
        outItems: MutableList<MediaItem>,
        outContainers: MutableList<String>
    ) {
        try {
            val soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "  <s:Body>\n" +
                    "    <u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\n" +
                    "      <ObjectID>$objectId</ObjectID>\n" +
                    "      <BrowseFlag>BrowseDirectChildren</BrowseFlag>\n" +
                    "      <Filter>*</Filter>\n" +
                    "      <StartingIndex>0</StartingIndex>\n" +
                    "      <RequestedCount>250</RequestedCount>\n" +
                    "      <SortCriteria></SortCriteria>\n" +
                    "    </u:Browse>\n" +
                    "  </s:Body>\n" +
                    "</s:Envelope>"

            val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Access-Method", "manual")
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(soapBody)
                writer.flush()
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                parseDidlLite(resp, outItems, outContainers)
            }
        } catch (_: Exception) {}
    }

    private fun parseDidlLite(xml: String, outList: MutableList<MediaItem>, outContainers: MutableList<String>) {
        try {
            val resultStart = xml.indexOf("<Result>")
            val resultEnd = xml.indexOf("</Result>")
            val rawDidl = if (resultStart != -1 && resultEnd != -1) {
                xml.substring(resultStart + 8, resultEnd)
            } else {
                xml
            }

            val didl = rawDidl
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(didl.toByteArray(Charsets.UTF_8)), "UTF-8")

            var eventType = parser.eventType
            var currentId = ""
            var currentTitle = ""
            var currentUrl = ""
            var currentThumbUrl = ""
            var currentSize = 0L
            var isVideo = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("container", ignoreCase = true) || tagName.endsWith(":container", ignoreCase = true)) {
                            val cid = parser.getAttributeValue(null, "id") ?: ""
                            if (cid.isNotEmpty()) outContainers.add(cid)
                        } else if (tagName.equals("item", ignoreCase = true) || tagName.endsWith(":item", ignoreCase = true)) {
                            currentId = parser.getAttributeValue(null, "id") ?: ""
                        } else if (tagName.equals("title", ignoreCase = true) || tagName.endsWith(":title", ignoreCase = true)) {
                            currentTitle = parser.nextText().trim()
                        } else if (tagName.equals("class", ignoreCase = true) || tagName.endsWith(":class", ignoreCase = true)) {
                            isVideo = parser.nextText().contains("video", ignoreCase = true)
                        } else if (tagName.equals("res", ignoreCase = true)) {
                            val sz = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                            val resUrl = parser.nextText().trim()
                            if (resUrl.contains("thumb", ignoreCase = true) || resUrl.contains("screen", ignoreCase = true)) {
                                currentThumbUrl = resUrl
                            } else if (resUrl.startsWith("http")) {
                                currentUrl = resUrl
                                if (sz > 0L) currentSize = sz
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            if (currentUrl.isNotEmpty()) {
                                if (currentThumbUrl.isEmpty()) currentThumbUrl = currentUrl
                                if (!currentTitle.contains(".")) {
                                    currentTitle += if (isVideo) ".MP4" else ".JPG"
                                }
                                outList.add(
                                    MediaItem(
                                        id = currentId.ifEmpty { currentUrl },
                                        title = currentTitle,
                                        url = currentUrl,
                                        thumbnailUrl = currentThumbUrl,
                                        fileSize = currentSize,
                                        date = ""
                                    )
                                )
                            }
                            currentId = ""; currentTitle = ""; currentUrl = ""; currentThumbUrl = ""; currentSize = 0L; isVideo = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "DIDL parse error", e)
        }
    }

    suspend fun getDeviceInformation(cam: CameraInfo): Pair<String, CameraSettings> = withContext(Dispatchers.IO) {
        val settings = CameraSettings()
        var streamUrl = ""

        val actionNames = listOf("GetInfomation", "GetInformation", "GetInfomationAct", "GetInformationAct")
        for (act in actionNames) {
            val resp = sendSoapActionWithResponse(cam, act, "<GPSINFO></GPSINFO>")
            if (resp.isNotEmpty()) {
                val unescaped = resp.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")

                val highMjpg = Regex("<QualityHighMjpgUrl>(.*?)</QualityHighMjpgUrl>").find(unescaped)?.groupValues?.get(1)?.trim()
                val lowMjpg = Regex("<QualityLowMjpgUrl>(.*?)</QualityLowMjpgUrl>").find(unescaped)?.groupValues?.get(1)?.trim()
                val highUrl = Regex("<QualityHighUrl>(.*?)</QualityHighUrl>").find(unescaped)?.groupValues?.get(1)?.trim()
                val lowUrl = Regex("<QualityLowUrl>(.*?)</QualityLowUrl>").find(unescaped)?.groupValues?.get(1)?.trim()

                streamUrl = when {
                    !highMjpg.isNullOrEmpty() -> highMjpg
                    !lowMjpg.isNullOrEmpty() -> lowMjpg
                    !highUrl.isNullOrEmpty() -> highUrl
                    !lowUrl.isNullOrEmpty() -> lowUrl
                    else -> ""
                }

                Regex("<DialModeValue>.*?<Default>(.*?)</Default>", RegexOption.DOT_MATCHES_ALL).find(unescaped)?.groupValues?.get(1)?.trim()?.let {
                    if (it.isNotEmpty()) settings.mode = it
                }
                Regex("<ISOValue>.*?<Default>(.*?)</Default>", RegexOption.DOT_MATCHES_ALL).find(unescaped)?.groupValues?.get(1)?.trim()?.let {
                    if (it.isNotEmpty()) settings.iso = it
                }
                Regex("<ShutterSpeedValue>.*?<Default>(.*?)</Default>", RegexOption.DOT_MATCHES_ALL).find(unescaped)?.groupValues?.get(1)?.trim()?.let {
                    if (it.isNotEmpty()) settings.shutterSpeed = it
                }
                Regex("<ApertureValue>.*?<Default>(.*?)</Default>", RegexOption.DOT_MATCHES_ALL).find(unescaped)?.groupValues?.get(1)?.trim()?.let {
                    if (it.isNotEmpty()) settings.aperture = it
                }
                Regex("<EVValue>.*?<Default>(.*?)</Default>", RegexOption.DOT_MATCHES_ALL).find(unescaped)?.groupValues?.get(1)?.trim()?.let {
                    if (it.isNotEmpty()) settings.ev = it
                }
                break
            }
        }

        for (act in listOf("SetMovieStreamQuality", "SetStreamQuality")) {
            val sqResp = sendSoapActionWithResponse(cam, act, "<Quality>low</Quality>")
            if (sqResp.isNotEmpty()) {
                val u = sqResp.replace("&lt;", "<").replace("&gt;", ">")
                val mjpg = Regex("<QualityLowMjpgUrl>(.*?)</QualityLowMjpgUrl>").find(u)?.groupValues?.get(1)?.trim()
                if (!mjpg.isNullOrEmpty()) {
                    streamUrl = mjpg
                    break
                }
            }
        }
        if (streamUrl.isEmpty()) {
            val sqResp = sendSoapActionWithResponse(cam, "SetStreamQuality", "<Quality>high</Quality>")
            if (sqResp.isNotEmpty()) {
                val u = sqResp.replace("&lt;", "<").replace("&gt;", ">")
                val h = Regex("<QualityHighUrl>(.*?)</QualityHighUrl>").find(u)?.groupValues?.get(1)?.trim()
                val l = Regex("<QualityLowUrl>(.*?)</QualityLowUrl>").find(u)?.groupValues?.get(1)?.trim()
                if (!h.isNullOrEmpty()) streamUrl = h
                else if (!l.isNullOrEmpty()) streamUrl = l
            }
        }

        Pair(streamUrl, settings)
    }

    suspend fun triggerShutter(cam: CameraInfo): Boolean = withContext(Dispatchers.IO) {
        sendSoapActionWithResponse(cam, "Shot", "<REQUESTIMAGE></REQUESTIMAGE>").isNotEmpty() ||
                sendSoapActionWithResponse(cam, "ShotAct", "<REQUESTIMAGE></REQUESTIMAGE>").isNotEmpty()
    }

    suspend fun touchAutofocus(cam: CameraInfo, x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val arg = "<AFPOSITION>${x}x${y}</AFPOSITION>"
        sendSoapActionWithResponse(cam, "touchAF", arg).isNotEmpty() ||
                sendSoapActionWithResponse(cam, "touchAFPoint", arg).isNotEmpty() ||
                sendSoapActionWithResponse(cam, "AF", "<AF_MF>0</AF_MF>").isNotEmpty()
    }

    suspend fun zoomIn(cam: CameraInfo): Boolean = withContext(Dispatchers.IO) {
        sendSoapActionWithResponse(cam, "ZoomIN", "<CURRENTZOOM>1</CURRENTZOOM>").isNotEmpty() ||
                sendSoapActionWithResponse(cam, "StartZoom", "<ZoomDirection>IN</ZoomDirection>").isNotEmpty()
    }

    suspend fun zoomOut(cam: CameraInfo): Boolean = withContext(Dispatchers.IO) {
        sendSoapActionWithResponse(cam, "ZoomOUT", "<CURRENTZOOM>1</CURRENTZOOM>").isNotEmpty() ||
                sendSoapActionWithResponse(cam, "StartZoom", "<ZoomDirection>OUT</ZoomDirection>").isNotEmpty()
    }

    suspend fun setParameter(cam: CameraInfo, actionName: String, paramTag: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val cleanAction = actionName.removeSuffix("Act")
        val payload = "<$paramTag>$value</$paramTag>"
        sendSoapActionWithResponse(cam, cleanAction, payload).isNotEmpty() ||
                sendSoapActionWithResponse(cam, actionName, payload).isNotEmpty()
    }

    private fun sendSoapActionWithResponse(cam: CameraInfo, actionName: String, actionBody: String): String {
        val candidateUrls = listOf(
            cam.deviceControlUrl,
            cam.contentDirUrl,
            "http://${cam.ip}:7679/smp_4_",
            "http://${cam.ip}:7679/smp_7_",
            "http://${cam.ip}:7679/upnp/control/ContentDirectory1"
        ).filter { it.isNotEmpty() }.distinct()

        val candidateUrns = listOf(
            cam.deviceControlUrn,
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:schemas-upnp-org:service:DeviceControl:1"
        ).filter { it.isNotEmpty() }.distinct()

        for (ctrlUrl in candidateUrls) {
            for (urn in candidateUrns) {
                try {
                    val soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                            "  <s:Body>\n" +
                            "    <u:$actionName xmlns:u=\"$urn\">\n" +
                            "      $actionBody\n" +
                            "    </u:$actionName>\n" +
                            "  </s:Body>\n" +
                            "</s:Envelope>"

                    val conn = (URL(ctrlUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 4000
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                        setRequestProperty("SOAPACTION", "\"$urn#$actionName\"")
                        setRequestProperty("User-Agent", USER_AGENT)
                        setRequestProperty("Access-Method", "manual")
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                        writer.write(soap)
                        writer.flush()
                    }

                    if (conn.responseCode == 200) {
                        return conn.inputStream.bufferedReader().use { it.readText() }
                    }
                } catch (_: Exception) {}
            }
        }
        return ""
    }
}
