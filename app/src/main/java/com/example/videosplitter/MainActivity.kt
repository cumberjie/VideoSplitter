package com.example.videosplitter

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectVideo: Button
    private lateinit var btnSplit: Button
    private lateinit var tvSelectedVideo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etInterval: EditText
    private lateinit var progressBar: ProgressBar
    private var selectedVideoPath: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleVideoSelection(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnSplit = findViewById(R.id.btnSplit)
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo)
        tvStatus = findViewById(R.id.tvStatus)
        etInterval = findViewById(R.id.etInterval)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupClickListeners() {
        btnSelectVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }
        btnSplit.setOnClickListener { startSplitting() }
    }

    private fun handleVideoSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            tvSelectedVideo.text = "已选择: $fileName"
            val inputFile = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }
            selectedVideoPath = inputFile.absolutePath
            btnSplit.isEnabled = true
            tvStatus.text = "视频已准备好，请设置分割间隔"
        } catch (e: Exception) {
            tvStatus.text = "选择视频失败: ${e.message}"
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "未知文件"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) name = cursor.getString(nameIndex)
        }
        return name
    }

    private fun startSplitting() {
        val intervalText = etInterval.text.toString()
        if (intervalText.isEmpty()) { tvStatus.text = "请输入分割间隔秒数"; return }
        val interval = intervalText.toIntOrNull()
        if (interval == null || interval <= 0) { tvStatus.text = "请输入有效的秒数"; return }
        if (selectedVideoPath == null) { tvStatus.text = "请先选择视频"; return }

        val outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VideoSplitter")
        if (!outputDir.exists()) outputDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        val outputPattern = File(outputDir, "split_${timestamp}_%03d.mp4").absolutePath

        progressBar.visibility = View.VISIBLE
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false
        tvStatus.text = "正在分割视频，请稍候..."

        val command = "-i \"$selectedVideoPath\" -c copy -map 0 -segment_time $interval -f segment -reset_timestamps 1 \"$outputPattern\""

        executor.execute {
            val session = FFmpegKit.execute(command)
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnSplit.isEnabled = true
                btnSelectVideo.isEnabled = true
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val count = outputDir.listFiles { f -> f.name.startsWith("split_$timestamp") }?.size ?: 0
                    tvStatus.text = "✅ 分割完成！\n生成了 $count 个视频片段\n保存位置: Movies/VideoSplitter"
                } else {
                    tvStatus.text = "❌ 分割失败 (错误码: ${session.returnCode})"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
