package com.samsungnx.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.samsungnx.camera.model.CameraConstants
import com.samsungnx.camera.model.CameraInfo
import com.samsungnx.camera.model.CameraSettings
import com.samsungnx.camera.model.MediaItem
import com.samsungnx.camera.network.AutoShareManager
import com.samsungnx.camera.network.CameraNetworkManager
import com.samsungnx.camera.network.CameraWifiInfo
import com.samsungnx.camera.network.ModeHandshake
import com.samsungnx.camera.network.RemoteViewfinderManager
import com.samsungnx.camera.network.SamsungUpnpClient
import com.samsungnx.camera.ui.MediaAdapter
import com.samsungnx.camera.ui.MediaDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var networkManager: CameraNetworkManager
    private val modeHandshake = ModeHandshake()
    private lateinit var upnpClient: SamsungUpnpClient
    private lateinit var mediaDownloader: MediaDownloader
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var rvfManager: RemoteViewfinderManager
    private lateinit var autoShareManager: AutoShareManager

    private var currentCamera: CameraInfo? = null
    private var currentDefaultUrl: String = ""
    private var cameraSettings = CameraSettings()
    private var isConnected = false

    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnWifiSettings: MaterialButton
    private lateinit var btnConnect: MaterialButton
    private lateinit var tabLayout: TabLayout

    private lateinit var layoutGallery: LinearLayout
    private lateinit var layoutRvf: LinearLayout
    private lateinit var layoutAutoShare: LinearLayout

    private lateinit var cbSelectAll: CheckBox
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnRefreshGallery: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var layoutOverallProgress: LinearLayout
    private lateinit var progressBarOverall: ProgressBar
    private lateinit var tvOverallProgress: TextView
    private lateinit var recyclerViewMedia: RecyclerView

    private lateinit var chipMode: TextView
    private lateinit var btnAperture: Button
    private lateinit var btnShutterSpeed: Button
    private lateinit var btnIso: Button
    private lateinit var btnEv: Button
    private lateinit var btnWb: Button
    private lateinit var viewfinderContainer: FrameLayout
    private lateinit var videoViewLive: VideoView
    private lateinit var ivLiveView: ImageView
    private lateinit var viewFocusReticle: View
    private lateinit var tvStreamHint: TextView
    private lateinit var btnShutter: MaterialButton
    private lateinit var btnZoomIn: MaterialButton
    private lateinit var btnZoomOut: MaterialButton

    private lateinit var switchAutoShare: MaterialSwitch
    private lateinit var tvAutoShareStatus: TextView
    private lateinit var recyclerViewAutoShare: RecyclerView
    private lateinit var autoShareAdapter: MediaAdapter
    private val autoShareItems = mutableListOf<MediaItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        networkManager = CameraNetworkManager(this)
        upnpClient = SamsungUpnpClient()
        mediaDownloader = MediaDownloader(this)
        rvfManager = RemoteViewfinderManager(upnpClient, lifecycleScope)
        autoShareManager = AutoShareManager(mediaDownloader, lifecycleScope)

        initViews()
        setupListeners()
        checkPermissions()

        networkManager.startListening()
    }

    private fun initViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        btnWifiSettings = findViewById(R.id.btnWifiSettings)
        btnConnect = findViewById(R.id.btnConnect)
        tabLayout = findViewById(R.id.tabLayout)

        layoutGallery = findViewById(R.id.layoutGallery)
        layoutRvf = findViewById(R.id.layoutRvf)
        layoutAutoShare = findViewById(R.id.layoutAutoShare)

        cbSelectAll = findViewById(R.id.cbSelectAll)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnRefreshGallery = findViewById(R.id.btnRefreshGallery)
        btnDownload = findViewById(R.id.btnDownload)
        layoutOverallProgress = findViewById(R.id.layoutOverallProgress)
        progressBarOverall = findViewById(R.id.progressBarOverall)
        tvOverallProgress = findViewById(R.id.tvOverallProgress)
        recyclerViewMedia = findViewById(R.id.recyclerViewMedia)

        mediaAdapter = MediaAdapter(lifecycleScope) { _, _ ->
            updateSelectedCount()
        }
        recyclerViewMedia.layoutManager = GridLayoutManager(this, 3)
        recyclerViewMedia.adapter = mediaAdapter

        chipMode = findViewById(R.id.chipMode)
        btnAperture = findViewById(R.id.btnAperture)
        btnShutterSpeed = findViewById(R.id.btnShutterSpeed)
        btnIso = findViewById(R.id.btnIso)
        btnEv = findViewById(R.id.btnEv)
        btnWb = findViewById(R.id.btnWb)
        viewfinderContainer = findViewById(R.id.viewfinderContainer)
        videoViewLive = findViewById(R.id.videoViewLive)
        ivLiveView = findViewById(R.id.ivLiveView)
        viewFocusReticle = findViewById(R.id.viewFocusReticle)
        tvStreamHint = findViewById(R.id.tvStreamHint)
        btnShutter = findViewById(R.id.btnShutter)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)

        switchAutoShare = findViewById(R.id.switchAutoShare)
        tvAutoShareStatus = findViewById(R.id.tvAutoShareStatus)
        recyclerViewAutoShare = findViewById(R.id.recyclerViewAutoShare)
        autoShareAdapter = MediaAdapter(lifecycleScope) { _, _ -> }
        recyclerViewAutoShare.layoutManager = LinearLayoutManager(this)
        recyclerViewAutoShare.adapter = autoShareAdapter
    }

    private fun setupListeners() {
        btnWifiSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {}
        }

        btnConnect.setOnClickListener {
            connectCamera()
        }

        btnRefreshGallery.setOnClickListener {
            val cam = currentCamera
            if (cam != null) {
                loadGalleryPhotos(cam)
            } else {
                connectCamera()
            }
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> switchTab(0)
                    1 -> switchTab(1)
                    2 -> switchTab(2)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // ModeHandshake events from Camera Port 7789 & 18100
        modeHandshake.onCameraModeEvent = { mode, _ ->
            runOnUiThread {
                tvConnectionStatus.text = "Kamera aktif dalam mode: ${mode.uppercase()}. Memuat fitur..."
                lifecycleScope.launch {
                    delay(600)
                    currentCamera?.let { cam ->
                        if (mode == "rvf") {
                            val (streamUrl, settings) = upnpClient.getDeviceInformation(cam)
                            if (streamUrl.isNotEmpty()) cam.streamUrl = streamUrl
                            cameraSettings = settings
                            updateSettingsUi()
                            tabLayout.getTabAt(1)?.select()
                        } else if (mode == "autoshare") {
                            tabLayout.getTabAt(2)?.select()
                        } else {
                            loadGalleryPhotos(cam)
                        }
                    }
                }
            }
        }

        modeHandshake.onPushPhotoReceived = { fileName, bytes ->
            lifecycleScope.launch {
                val saved = mediaDownloader.saveImageBytes(fileName, bytes)
                if (saved) {
                    withContext(Dispatchers.Main) {
                        onNewAutoPhotoSaved(fileName, bytes.size.toLong())
                    }
                }
            }
        }

        networkManager.onWifiAvailableListener = { info ->
            runOnUiThread {
                if (networkManager.isSamsungCameraNetwork(info) && !isConnected) {
                    tvConnectionStatus.text = "Wi-Fi Kamera terdeteksi (${info.localIp} -> ${info.gatewayIp}). Menghubungkan..."
                    connectCamera(info)
                }
            }
        }

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            mediaAdapter.selectAll(isChecked)
            updateSelectedCount()
        }

        btnDownload.setOnClickListener {
            startDownloadSelected()
        }

        btnShutter.setOnClickListener {
            triggerShutter()
        }

        btnZoomIn.setOnClickListener {
            val cam = currentCamera ?: return@setOnClickListener
            lifecycleScope.launch {
                rvfManager.zoomIn(cam)
            }
        }

        btnZoomOut.setOnClickListener {
            val cam = currentCamera ?: return@setOnClickListener
            lifecycleScope.launch {
                rvfManager.zoomOut(cam)
            }
        }

        viewfinderContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTouchAutofocus(event.x, event.y)
            }
            true
        }

        btnIso.setOnClickListener { showSettingDialog("Pilih ISO", CameraConstants.ISO_OPTIONS) { chosen ->
            btnIso.text = "ISO $chosen"
            setCameraParam("SetISO", "ISOValue", chosen)
        }}

        btnAperture.setOnClickListener { showSettingDialog("Pilih Aperture (Diafragma)", CameraConstants.APERTURE_OPTIONS) { chosen ->
            btnAperture.text = chosen
            setCameraParam("SetAperture", "ApertureValue", chosen)
        }}

        btnShutterSpeed.setOnClickListener { showSettingDialog("Pilih Shutter Speed", CameraConstants.SHUTTER_OPTIONS) { chosen ->
            btnShutterSpeed.text = chosen
            setCameraParam("SetShutterSpeed", "ShutterSpeedValue", chosen)
        }}

        btnEv.setOnClickListener { showSettingDialog("Pilih Kompensasi EV", CameraConstants.EV_OPTIONS) { chosen ->
            btnEv.text = "EV $chosen"
            setCameraParam("SetEV", "EVValue", chosen)
        }}

        btnWb.setOnClickListener { showSettingDialog("Pilih White Balance", CameraConstants.WB_OPTIONS) { chosen ->
            btnWb.text = "WB $chosen"
            setCameraParam("SetWB", "WBValue", chosen)
        }}

        rvfManager.onFrameAvailable = { bitmap ->
            tvStreamHint.visibility = View.GONE
            ivLiveView.visibility = View.VISIBLE
            ivLiveView.setImageBitmap(bitmap)
        }

        rvfManager.onError = { msg ->
            tvStreamHint.text = "Live View: $msg"
            tvStreamHint.visibility = View.VISIBLE
        }

        switchAutoShare.setOnCheckedChangeListener { _, isChecked ->
            val info = networkManager.forceBindToCurrentWifi()
            val camIp = currentCamera?.ip ?: info.gatewayIp
            if (isChecked) {
                lifecycleScope.launch {
                    autoShareManager.startAutoShare(camIp, info.localIp)
                }
            } else {
                lifecycleScope.launch {
                    autoShareManager.stopAutoShare(camIp)
                }
            }
        }

        autoShareManager.onStatusChanged = { status ->
            tvAutoShareStatus.text = status
        }

        autoShareManager.onPhotoReceived = { fileName, fileSize ->
            onNewAutoPhotoSaved(fileName, fileSize)
        }
    }

    private fun onNewAutoPhotoSaved(fileName: String, fileSize: Long) {
        Toast.makeText(this, "Foto masuk tersimpan ke Galeri: $fileName", Toast.LENGTH_LONG).show()
        tvConnectionStatus.text = "✓ Foto $fileName berhasil disimpan ke Galeri (DCIM/SamsungNX)!"
        val newItem = MediaItem(
            id = fileName,
            title = fileName,
            url = "",
            thumbnailUrl = "",
            fileSize = fileSize,
            date = "Tersimpan di Galeri"
        )
        autoShareItems.add(0, newItem)
        autoShareAdapter.submitList(autoShareItems.toList())
    }

    private fun switchTab(index: Int) {
        layoutGallery.visibility = if (index == 0) View.VISIBLE else View.GONE
        layoutRvf.visibility = if (index == 1) View.VISIBLE else View.GONE
        layoutAutoShare.visibility = if (index == 2) View.VISIBLE else View.GONE

        if (index == 1 && isConnected) {
            startLiveView()
        } else {
            rvfManager.stopLiveStream()
        }
    }

    private fun connectCamera(wifiInfo: CameraWifiInfo? = null) {
        lifecycleScope.launch {
            tvConnectionStatus.text = getString(R.string.camera_status_searching)
            btnConnect.isEnabled = false

            // 1. Force bind process to Camera Wi-Fi so cellular data doesn't hijack sockets
            val info = wifiInfo ?: networkManager.forceBindToCurrentWifi()
            val localIp = if (info.localIp != "0.0.0.0") info.localIp else "192.168.107.11"
            val cameraIp = if (info.gatewayIp != "0.0.0.0") info.gatewayIp else "192.168.107.1"

            // 2. Always start AutoShare & SelectivePush background listeners immediately
            if (switchAutoShare.isChecked) {
                autoShareManager.startAutoShare(cameraIp, localIp)
            }

            // 3. Perform ModeHandshake (7788 -> 7789 / 8100 -> 18100) to unlock camera waiting screen!
            val handshake = modeHandshake.performHandshake(cameraIp, localIp)
            btnConnect.isEnabled = true

            if (handshake.isConnected) {
                isConnected = true
                currentDefaultUrl = handshake.defaultUrl
                btnConnect.text = "Tes Ulang"

                // 4. Discover UPnP endpoints with mandatory Samsung headers
                val camInfo = upnpClient.discoverCamera(cameraIp, currentDefaultUrl)
                currentCamera = camInfo

                tvConnectionStatus.text = "Terhubung: ${camInfo.modelName} ($cameraIp) | ${handshake.statusLog}"

                // 5. Fetch RVF stream URL and camera settings if available
                val (streamUrl, settings) = upnpClient.getDeviceInformation(camInfo)
                if (streamUrl.isNotEmpty()) {
                    camInfo.streamUrl = streamUrl
                }
                cameraSettings = settings
                updateSettingsUi()

                // 6. Load Gallery Photos
                delay(400)
                loadGalleryPhotos(camInfo)

                // 7. Auto-switch tab if camera told us its mode
                if (handshake.detectedMode == "rvf" || tabLayout.selectedTabPosition == 1) {
                    startLiveView()
                }
            } else {
                isConnected = false
                tvConnectionStatus.text = "Belum merespon di $cameraIp (IP HP: $localIp). Pastikan Wi-Fi HP terhubung ke AP_SSC_NX3000!"
            }
        }
    }

    private fun loadGalleryPhotos(cam: CameraInfo) {
        lifecycleScope.launch {
            val items = upnpClient.browseMedia(cam.ip, cam.contentDirUrl)
            mediaAdapter.submitList(items)
            if (items.isNotEmpty()) {
                tvConnectionStatus.text = "Terhubung: ${cam.modelName} (${items.size} foto siap diunduh)"
            } else {
                tvConnectionStatus.text = "Terhubung ke ${cam.ip}. Jika memilih foto dari layar kamera, tekan 'Send' di kamera untuk kirim otomatis!"
            }
        }
    }

    private fun updateSelectedCount() {
        val selected = mediaAdapter.getSelectedItems()
        tvSelectedCount.text = "${selected.size} dipilih"
        btnDownload.isEnabled = selected.isNotEmpty()
    }

    private fun startDownloadSelected() {
        val selected = mediaAdapter.getSelectedItems()
        if (selected.isEmpty()) return

        layoutOverallProgress.visibility = View.VISIBLE
        progressBarOverall.progress = 0
        btnDownload.isEnabled = false

        lifecycleScope.launch {
            var completedCount = 0
            val total = selected.size

            for ((index, item) in selected.withIndex()) {
                tvOverallProgress.text = "Mengunduh (${index + 1}/$total): ${item.title}"
                mediaDownloader.downloadItem(item) { itemProgress ->
                    item.downloadProgress = itemProgress
                    mediaAdapter.notifyDataSetChanged()
                }
                completedCount++
                progressBarOverall.progress = ((completedCount * 100) / total)
            }

            tvOverallProgress.text = "Selesai mengunduh $completedCount foto ke Galeri (DCIM/SamsungNX)."
            btnDownload.isEnabled = true
            Toast.makeText(this@MainActivity, "Berhasil mengunduh $completedCount foto!", Toast.LENGTH_LONG).show()
        }
    }

    private fun startLiveView() {
        val cam = currentCamera ?: return
        lifecycleScope.launch {
            if (cam.streamUrl.isEmpty()) {
                val (sUrl, settings) = upnpClient.getDeviceInformation(cam)
                if (sUrl.isNotEmpty()) cam.streamUrl = sUrl
                cameraSettings = settings
                updateSettingsUi()
            }

            val url = cam.streamUrl
            if (url.startsWith("rtsp://", ignoreCase = true)) {
                ivLiveView.visibility = View.GONE
                videoViewLive.visibility = View.VISIBLE
                tvStreamHint.text = "Memulai RTSP live stream..."
                videoViewLive.setVideoURI(Uri.parse(url))
                videoViewLive.setOnPreparedListener {
                    tvStreamHint.visibility = View.GONE
                    videoViewLive.start()
                }
                videoViewLive.setOnErrorListener { _, _, _ ->
                    videoViewLive.visibility = View.GONE
                    ivLiveView.visibility = View.VISIBLE
                    rvfManager.startLiveStream(cam.ip, url)
                    true
                }
            } else {
                videoViewLive.visibility = View.GONE
                ivLiveView.visibility = View.VISIBLE
                tvStreamHint.text = "Menghubungi Live View kamera (${cam.ip})..."
                rvfManager.startLiveStream(cam.ip, url)
            }
        }
    }

    private fun handleTouchAutofocus(touchX: Float, touchY: Float) {
        val cam = currentCamera ?: return
        val width = viewfinderContainer.width
        val height = viewfinderContainer.height
        if (width <= 0 || height <= 0) return

        val normX = touchX / width.toFloat()
        val normY = touchY / height.toFloat()

        viewFocusReticle.x = touchX - (viewFocusReticle.width / 2)
        viewFocusReticle.y = touchY - (viewFocusReticle.height / 2)
        viewFocusReticle.visibility = View.VISIBLE
        viewFocusReticle.alpha = 1.0f
        viewFocusReticle.animate().alpha(0.0f).setDuration(1200).start()

        lifecycleScope.launch {
            val success = rvfManager.touchAutofocus(cam, normX, normY)
            if (success) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Autofokus Terkunci", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun triggerShutter() {
        val cam = currentCamera ?: return
        btnShutter.isEnabled = false
        lifecycleScope.launch {
            val ok = rvfManager.triggerShutter(cam)
            btnShutter.isEnabled = true
            if (ok) {
                Toast.makeText(this@MainActivity, "Jepret! Foto berhasil diambil.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Gagal memicu shutter (pastikan kamera berada di mode Remote Viewfinder).", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setCameraParam(actionName: String, paramTag: String, value: String) {
        val cam = currentCamera ?: return
        lifecycleScope.launch {
            upnpClient.setParameter(cam, actionName, paramTag, value)
        }
    }

    private fun updateSettingsUi() {
        chipMode.text = "MODE: ${cameraSettings.mode}"
        btnAperture.text = cameraSettings.aperture
        btnShutterSpeed.text = cameraSettings.shutterSpeed
        btnIso.text = "ISO ${cameraSettings.iso}"
        btnEv.text = "EV ${cameraSettings.ev}"
        btnWb.text = "WB ${cameraSettings.wb}"
    }

    private fun showSettingDialog(title: String, options: List<String>, onSelected: (String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                onSelected(options[which])
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkManager.stopListening()
        modeHandshake.stop()
        rvfManager.stopLiveStream()
    }
}
