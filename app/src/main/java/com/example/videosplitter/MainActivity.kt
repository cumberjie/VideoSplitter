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
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectVideo: Button
    private lateinit var btnSplit: Button
    private lateinit var tvSelectedVideo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etInterval: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var progressContainer: LinearLayout
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvProgressDetail: TextView
    
    // 快捷秒数选择按钮
    private lateinit var btn3s: Button
    private lateinit var btn4s: Button
    private lateinit var btn5s: Button
    private lateinit var btn10s: Button
    
    private var selectedVideoPath: String? = null
    private var originalFileName: String = "video"  // 保存原始文件名（不含扩展名）
    private var videoDurationMs: Long = 0  // 视频总时长（毫秒）
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
        progressContainer = findViewById(R.id.progressContainer)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        
        // 快捷秒数按钮
        btn3s = findViewById(R.id.btn3s)
        btn4s = findViewById(R.id.btn4s)
        btn5s = findViewById(R.id.btn5s)
        btn10s = findViewById(R.id.btn10s)
    }

    private fun setupClickListeners() {
        btnSelectVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }
        btnSplit.setOnClickListener { startSplitting() }
        
        // 快捷秒数选择按钮 - 点击后填入输入框
        btn3s.setOnClickListener { etInterval.setText("3") }
        btn4s.setOnClickListener { etInterval.setText("4") }
        btn5s.setOnClickListener { etInterval.setText("5") }
        btn10s.setOnClickListener { etInterval.setText("10") }
    }

    private fun handleVideoSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            tvSelectedVideo.text = "已选择: $fileName"
            
            // 保存原始文件名（去掉扩展名）
            originalFileName = fileName.substringBeforeLast(".")
            
            // 复制视频到缓存目录
            val inputFile = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }
            selectedVideoPath = inputFile.absolutePath
            
            // 获取视频时长
            videoDurationMs = getVideoDuration(inputFile.absolutePath)
            
            btnSplit.isEnabled = true
            val durationStr = formatDuration(videoDurationMs)
            tvStatus.text = "视频已准备好 (时长: $durationStr)\n请设置分割间隔后点击分割"
            
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

    /**
     * 获取视频时长（毫秒）
     */
    private fun getVideoDuration(path: String): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 格式化时长显示
     */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun startSplitting() {
        // 验证输入
        val intervalText = etInterval.text.toString()
        if (intervalText.isEmpty()) {
            tvStatus.text = "请输入分割间隔秒数"
            return
        }
        val interval = intervalText.toIntOrNull()
        if (interval == null || interval <= 0) {
            tvStatus.text = "请输入有效的秒数"
            return
        }
        if (selectedVideoPath == null) {
            tvStatus.text = "请先选择视频"
            return
        }

        // 创建输出目录
        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "VideoSplitter"
        )
        if (!outputDir.exists()) outputDir.mkdirs()

        // 更新 UI 状态 - 显示美观的进度区域
        progressContainer.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0
        tvProgressPercent.text = "正在分割 0%"
        tvProgressDetail.text = "准备中..."
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false

        // 计算总共需要分割的段数
        val durationSec = videoDurationMs / 1000.0
        val totalSegments = ceil(durationSec / interval).toInt()
        
        tvStatus.text = "预计生成 $totalSegments 个片段"

        // 在后台线程执行分割
        executor.execute {
            var successCount = 0
            var failedCount = 0

            // ========== 逐段分割，确保精准时间 ==========
            for (i in 0 until totalSegments) {
                // 计算当前段的起始时间（秒）
                val startTimeSec = i * interval
                
                // 输出文件名：源文件名_01.mp4, 源文件名_02.mp4 ...
                val segmentNumber = String.format("%02d", i + 1)
                val outputFile = File(outputDir, "${originalFileName}_${segmentNumber}.mp4").absolutePath

                // 构建 FFmpeg 命令
                val command = "-ss $startTimeSec " +
                        "-i \"$selectedVideoPath\" " +
                        "-t $interval " +
                        "-c:v libx264 -crf 18 -preset fast " +
                        "-c:a aac -b:a 192k " +
                        "-avoid_negative_ts make_zero " +
                        "-y \"$outputFile\""

                // 更新进度 - 美观显示
                val currentSegment = i + 1
                val progress = ((currentSegment.toFloat() / totalSegments) * 100).toInt()
                
                runOnUiThread {
                    progressBar.progress = progress
                    tvProgressPercent.text = "正在分割 $progress%"
                    tvProgressDetail.text = "处理第 $currentSegment / $totalSegments 段"
                }

                // 执行 FFmpeg 命令
                val session = FFmpegKit.execute(command)
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    successCount++
                } else {
                    failedCount++
                }
            }

            // 分割完成，更新 UI
            runOnUiThread {
                // 完成时显示100%
                progressBar.progress = 100
                tvProgressPercent.text = "分割完成 100%"
                tvProgressDetail.text = "处理完毕"
                
                // 延迟隐藏进度区域
                progressContainer.postDelayed({
                    progressContainer.visibility = View.GONE
                }, 2000)
                
                btnSplit.isEnabled = true
                btnSelectVideo.isEnabled = true

                if (failedCount == 0) {
                    tvStatus.text = "✅ 分割完成！\n" +
                            "生成了 $successCount 个视频片段\n" +
                            "每段精准 ${etInterval.text} 秒\n" +
                            "文件命名: ${originalFileName}_01 ~ ${originalFileName}_${String.format("%02d", successCount)}\n" +
                            "保存位置: Movies/VideoSplitter"
                } else {
                    tvStatus.text = "⚠️ 分割部分完成\n" +
                            "成功: $successCount 个\n" +
                            "失败: $failedCount 个\n" +
                            "保存位置: Movies/VideoSplitter"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
