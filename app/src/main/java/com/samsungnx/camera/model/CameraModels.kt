package com.samsungnx.camera.model

data class MediaItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val fileSize: Long,
    val date: String,
    var isSelected: Boolean = false,
    var downloadProgress: Int = 0
)

data class CameraInfo(
    val ip: String,
    val modelName: String,
    val contentDirUrl: String,
    val deviceControlUrl: String,
    val deviceControlUrn: String,
    var streamUrl: String = ""
)

data class CameraSettings(
    var mode: String = "P",
    var aperture: String = "F3.5",
    var shutterSpeed: String = "1/125",
    var iso: String = "Auto",
    var ev: String = "0.0",
    var wb: String = "Auto",
    var zoom: Int = 1
)

object CameraConstants {
    const val SAMSUNG_MAC = "a4:50:46:88:9d:27"
    const val USER_AGENT_RVF_ML = "SEC_RVF_ML_a4:50:46:88:9d:27"
    const val USER_AGENT_MODE = "SEC_MODE_a4:50:46:88:9d:27"
    const val USER_AGENT_SP = "SEC_SP_a4:50:46:88:9d:27"

    val ISO_OPTIONS = listOf("Auto", "100", "200", "400", "800", "1600", "3200")
    val APERTURE_OPTIONS = listOf("Auto", "F2.8", "F3.5", "F4.0", "F5.6", "F8.0", "F11", "F16")
    val SHUTTER_OPTIONS = listOf("Auto", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2")
    val EV_OPTIONS = listOf("+2.0", "+1.5", "+1.0", "+0.7", "+0.3", "0.0", "-0.3", "-0.7", "-1.0", "-1.5", "-2.0")
    val WB_OPTIONS = listOf("AUTO", "DAYLIGHT", "CLOUDY", "FLUORESCENT_W", "FLUORESCENT_N", "TUNGSTEN")
}
