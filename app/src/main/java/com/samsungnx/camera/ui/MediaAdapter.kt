package com.samsungnx.camera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.samsungnx.camera.R
import com.samsungnx.camera.model.CameraConstants
import com.samsungnx.camera.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat

class MediaAdapter(
    private val scope: CoroutineScope,
    private val onItemChecked: (MediaItem, Boolean) -> Unit
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private val memoryCache: LruCache<String, Bitmap>

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun selectAll(select: Boolean) {
        items.forEach { it.isSelected = select }
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<MediaItem> = items.filter { it.isSelected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val progressBarItem: ProgressBar = itemView.findViewById(R.id.progressBarItem)

        fun bind(item: MediaItem) {
            tvFileName.text = item.title
            tvFileSize.text = formatFileSize(item.fileSize)

            cbSelect.setOnCheckedChangeListener(null)
            cbSelect.isChecked = item.isSelected
            cbSelect.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                onItemChecked(item, isChecked)
            }

            itemView.setOnClickListener {
                cbSelect.isChecked = !cbSelect.isChecked
            }

            if (item.downloadProgress in 1..99) {
                progressBarItem.visibility = View.VISIBLE
                progressBarItem.progress = item.downloadProgress
            } else {
                progressBarItem.visibility = View.GONE
            }

            loadThumbnail(item.thumbnailUrl, ivThumbnail)
        }

        private fun loadThumbnail(url: String, imageView: ImageView) {
            if (url.isEmpty()) {
                imageView.setImageResource(android.R.drawable.ic_menu_camera)
                return
            }
            val cached = memoryCache.get(url)
            if (cached != null) {
                imageView.setImageBitmap(cached)
                return
            }

            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            imageView.tag = url

            scope.launch(Dispatchers.IO) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", CameraConstants.USER_AGENT_RVF_ML)
                        setRequestProperty("Access-Method", "manual")
                    }
                    if (conn.responseCode == 200) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 2
                        }
                        val bitmap = BitmapFactory.decodeStream(conn.inputStream, null, options)
                        if (bitmap != null) {
                            memoryCache.put(url, bitmap)
                            withContext(Dispatchers.Main) {
                                if (imageView.tag == url) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0L) return ""
            val df = DecimalFormat("#.##")
            return when {
                bytes >= 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
                bytes >= 1024 -> "${df.format(bytes / 1024.0)} KB"
                else -> "$bytes B"
            }
        }
    }
}
