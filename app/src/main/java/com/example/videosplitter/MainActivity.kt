package com.example.videosplitter

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

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
    private lateinit var spinnerProgress: ProgressBar
  
    // 快捷秒数选择按钮
    private lateinit var btn3s: Button
    private lateinit var btn4s: Button
    private lateinit var btn5s: Button
  
    private var selectedVideoPath: String? = null
    private var originalFileName: String = "video"
    private var videoDurationMs: Long = 0
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "VideoSplitter"  // 日志标签
    }

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
        spinnerProgress = findViewById(R.id.spinnerProgress)
      
        btn3s = findViewById(R.id.btn3s)
        btn4s = findViewById(R.id.btn4s)
        btn5s = findViewById(R.id.btn5s)
    }

    private fun setupClickListeners() {
        btnSelectVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }
        btnSplit.setOnClickListener { startSplitting() }
      
        btn3s.setOnClickListener { etInterval.setText("3") }
        btn4s.setOnClickListener { etInterval.setText("4") }
        btn5s.setOnClickListener { etInterval.setText("5") }
    }

    private fun handleVideoSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            tvSelectedVideo.text = "已选择: $fileName"
          
            originalFileName = fileName.substringBeforeLast(".")
          
            val inputFile = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }
            selectedVideoPath = inputFile.absolutePath
          
            videoDurationMs = getVideoDuration(inputFile.absolutePath)
          
            btnSplit.isEnabled = true
            val durationStr = formatDuration(videoDurationMs)
            tvStatus.text = "视频已准备好 (时长: $durationStr)\n请设置分割间隔后点击分割"
          
        } catch (e: Exception) {
            tvStatus.text = "选择视频失败: ${e.message}"
            Log.e(TAG, "视频选择失败", e)
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
            Log.e(TAG, "获取视频时长失败", e)
            0L
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun startSplitting() {
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

        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "VideoSplitter"
        )
        if (!outputDir.exists()) outputDir.mkdirs()

        progressContainer.visibility = View.VISIBLE
        spinnerProgress.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0
        tvProgressPercent.text = "正在分割 0%"
        tvProgressDetail.text = "准备中..."
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false

        val durationSec = videoDurationMs / 1000.0
        val totalSegments = ceil(durationSec / interval).toInt()
      
        tvStatus.text = "预计生成 $totalSegments 个片段"
        Log.i(TAG, "开始分割: 总时长=${durationSec}秒, 间隔=${interval}秒, 预计片段=$totalSegments")

        executor.execute {
            var successCount = 0
            var failedCount = 0
            // 收集错误信息，用于最终显示
            val errorMessages = mutableListOf<String>()

            for (i in 0 until totalSegments) {
                val startTimeSec = i * interval
                val currentSegment = i + 1
              
                val segmentNumber = String.format("%02d", currentSegment)
                val outputFile = File(outputDir, "${originalFileName}_${segmentNumber}.mp4").absolutePath

                val command = arrayOf(
                    "-ss", startTimeSec.toString(),
                    "-i", selectedVideoPath!!,
                    "-t", interval.toString(),
                    "-c:v", "libx264",
                    "-crf", "18",
                    "-preset", "fast",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-avoid_negative_ts", "make_zero",
                    "-y", outputFile
                )

                Log.d(TAG, "开始处理片段 $currentSegment: start=$startTimeSec, output=$outputFile")

                // 使用 CountDownLatch 等待异步任务完成
                val latch = CountDownLatch(1)
                var segmentSuccess = false
                var segmentError: String? = null
                
                // 目标时长（毫秒），用于计算编码进度
                val targetDurationMs = interval * 1000L

                // 使用异步执行，带统计回调实现精确进度
                FFmpegKit.executeWithArgumentsAsync(
                    command,
                    // 完成回调
                    { session ->
                        segmentSuccess = ReturnCode.isSuccess(session.returnCode)
                        
                        if (!segmentSuccess) {
                            // ========== 优化1: 捕获详细错误日志 ==========
                            segmentError = session.allLogsAsString
                            Log.e(TAG, "片段 $currentSegment 分割失败:")
                            Log.e(TAG, "返回码: ${session.returnCode}")
                            Log.e(TAG, "错误详情: $segmentError")
                        } else {
                            Log.i(TAG, "片段 $currentSegment 分割成功")
                        }
                        
                        // 释放锁，继续下一个片段
                        latch.countDown()
                    },
                    // 日志回调（用于调试）
                    { log ->
                        Log.v(TAG, log.message)
                    },
                    // ========== 优化3: 统计回调 - 实时编码进度 ==========
                    { statistics ->
                        val timeMs = statistics.time
                        if (timeMs > 0) {
                            // 计算当前片段的编码进度百分比
                            val segmentProgress = min((timeMs.toFloat() / targetDurationMs) * 100, 100f).toInt()
                            
                            // 计算总体进度：已完成片段 + 当前片段进度
                            val overallProgress = (((i.toFloat() + segmentProgress / 100f) / totalSegments) * 100).toInt()
                            
                            runOnUiThread {
                                progressBar.progress = overallProgress
                                tvProgressPercent.text = "正在分割 $overallProgress%"
                                tvProgressDetail.text = "片段 $currentSegment/$totalSegments 编码中: $segmentProgress%"
                            }
                        }
                    }
                )

                // 等待当前片段处理完成
                try {
                    latch.await()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "等待片段完成时被中断", e)
                    Thread.currentThread().interrupt()
                    break
                }

                // 统计结果
                if (segmentSuccess) {
                    successCount++
                } else {
                    failedCount++
                    // 保存简短错误信息用于UI显示
                    errorMessages.add("片段$currentSegment: ${extractErrorSummary(segmentError)}")
                }

                // 更新总进度（片段完成后）
                val overallProgress = ((currentSegment.toFloat() / totalSegments) * 100).toInt()
                runOnUiThread {
                    progressBar.progress = overallProgress
                    tvProgressPercent.text = "正在分割 $overallProgress%"
                    tvProgressDetail.text = "片段 $currentSegment/$totalSegments 完成"
                }
            }

            // 分割完成，更新UI
            runOnUiThread {
                progressBar.progress = 100
                tvProgressPercent.text = "分割完成 100%"
                tvProgressDetail.text = "处理完毕"
                spinnerProgress.visibility = View.GONE
              
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
                    Log.i(TAG, "分割全部成功: $successCount 个片段")
                } else {
                    // 显示错误摘要
                    val errorSummary = if (errorMessages.size <= 3) {
                        errorMessages.joinToString("\n")
                    } else {
                        errorMessages.take(3).joinToString("\n") + "\n...等${errorMessages.size}个错误"
                    }
                    
                    tvStatus.text = "⚠️ 分割部分完成\n" +
                            "成功: $successCount 个\n" +
                            "失败: $failedCount 个\n" +
                            "保存位置: Movies/VideoSplitter\n\n" +
                            "错误信息:\n$errorSummary"
                    Log.w(TAG, "分割部分完成: 成功=$successCount, 失败=$failedCount")
                }
            }
        }
    }

    /**
     * 从完整错误日志中提取简短摘要
     * 用于在UI上显示，避免过长
     */
    private fun extractErrorSummary(fullLog: String?): String {
        if (fullLog.isNullOrEmpty()) return "未知错误"
        
        // 尝试查找常见错误关键词
        val errorPatterns = listOf(
            "No such file" to "文件不存在",
            "Permission denied" to "权限被拒绝",
            "Invalid data" to "无效数据",
            "Encoder not found" to "编码器未找到",
            "Out of memory" to "内存不足",
            "No space left" to "存储空间不足",
            "Invalid argument" to "参数无效"
        )
        
        for ((pattern, message) in errorPatterns) {
            if (fullLog.contains(pattern, ignoreCase = true)) {
                return message
            }
        }
        
        // 如果没有匹配，返回日志最后一行（通常包含错误信息）
        val lastLine = fullLog.trim().lines().lastOrNull { it.isNotBlank() }
        return lastLine?.take(50) ?: "处理失败"
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
