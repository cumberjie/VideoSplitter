package com.example.videosplitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    
    // 【修复5】用于检查 Activity 是否已销毁
    private var isActivityDestroyed = false

    companion object {
        private const val TAG = "VideoSplitter"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MIN_LAST_SEGMENT_DURATION = 1.0
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleVideoSelection(it) }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStoragePermission()) {
            tvStatus.text = "权限已获取，请选择视频"
        } else {
            tvStatus.text = "⚠️ 未获得存储权限\n视频将保存到应用私有目录"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupClickListeners()
        checkAndRequestPermissions()
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

    // ==================== 权限处理 ====================

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasStoragePermission()) {
            tvStatus.text = "请选择视频文件"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showStoragePermissionDialog()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun showStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage("为了将分割后的视频保存到 Movies 文件夹，需要授予\"所有文件访问\"权限。\n\n点击\"去设置\"后，请开启\"允许访问所有文件\"选项。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storagePermissionLauncher.launch(intent)
                }
            }
            .setNegativeButton("稍后") { _, _ ->
                tvStatus.text = "⚠️ 未获得存储权限\n视频将保存到应用私有目录"
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                tvStatus.text = "权限已获取，请选择视频"
            } else {
                tvStatus.text = "⚠️ 未获得存储权限\n视频将保存到应用私有目录"
            }
        }
    }

    private fun getOutputDirectory(): File {
        val outputDir = if (hasStoragePermission()) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "VideoSplitter"
            )
        } else {
            File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoSplitter")
        }
        
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        return outputDir
    }

    private fun getOutputDisplayPath(): String {
        return if (hasStoragePermission()) {
            "Movies/VideoSplitter"
        } else {
            "Android/data/${packageName}/files/Movies/VideoSplitter"
        }
    }

    // ==================== 视频处理 ====================

    private fun handleVideoSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            tvSelectedVideo.text = "已选择: $fileName"
          
            originalFileName = fileName.substringBeforeLast(".")
            
            // 【修复2】清理旧的缓存文件
            cleanupCacheFiles()
          
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
    
    /**
     * 【修复2】清理缓存文件
     */
    private fun cleanupCacheFiles() {
        try {
            val cacheFile = File(cacheDir, "input_video.mp4")
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "已清理缓存文件: ${cacheFile.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理缓存文件失败", e)
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
     * 【修复3】使用 try-finally 确保 MediaMetadataRetriever 释放
     */
    private fun getVideoDuration(path: String): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "获取视频时长失败", e)
            0L
        } finally {
            // 确保释放资源，避免内存泄漏
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "释放 MediaMetadataRetriever 失败", e)
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun calculateSegments(durationSec: Double, interval: Int): Pair<Int, List<Double>> {
        val fullSegments = (durationSec / interval).toInt()
        val remainder = durationSec - (fullSegments * interval)
        
        val totalSegments: Int = when {
            remainder == 0.0 -> fullSegments
            remainder >= MIN_LAST_SEGMENT_DURATION -> fullSegments + 1
            fullSegments > 0 -> fullSegments
            else -> 1
        }
        
        val segmentDurations = mutableListOf<Double>()
        for (i in 0 until totalSegments) {
            val startTime = i * interval
            val duration = if (i == totalSegments - 1) {
                durationSec - startTime
            } else {
                interval.toDouble()
            }
            segmentDurations.add(duration)
        }
        
        return Pair(totalSegments, segmentDurations)
    }
    
    /**
     * 【修复5】安全的 UI 更新方法，避免 Activity 销毁后崩溃
     */
    private fun safeRunOnUiThread(action: () -> Unit) {
        if (!isActivityDestroyed && !isFinishing) {
            runOnUiThread(action)
        }
    }
    
    /**
     * 【修复9】检查输出文件是否已存在
     * @return 已存在的文件列表
     */
    private fun checkExistingFiles(outputDir: File, totalSegments: Int): List<String> {
        val existingFiles = mutableListOf<String>()
        for (i in 1..totalSegments) {
            val segmentNumber = String.format("%02d", i)
            val outputFile = File(outputDir, "${originalFileName}_${segmentNumber}.mp4")
            if (outputFile.exists()) {
                existingFiles.add(outputFile.name)
            }
        }
        return existingFiles
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
        
        // 【修复8】验证间隔不超过视频时长
        val durationSec = videoDurationMs / 1000.0
        if (interval > durationSec) {
            tvStatus.text = "⚠️ 分割间隔 (${interval}秒) 超过视频时长 (${String.format("%.1f", durationSec)}秒)\n请输入较小的值"
            return
        }

        val outputDir = getOutputDirectory()
        val displayPath = getOutputDisplayPath()
        
        // 预先计算分段信息
        val (totalSegments, segmentDurations) = calculateSegments(durationSec, interval)
        
        // 【修复9】检查是否有文件会被覆盖
        val existingFiles = checkExistingFiles(outputDir, totalSegments)
        if (existingFiles.isNotEmpty()) {
            val fileList = if (existingFiles.size <= 3) {
                existingFiles.joinToString("\n")
            } else {
                existingFiles.take(3).joinToString("\n") + "\n...等 ${existingFiles.size} 个文件"
            }
            
            AlertDialog.Builder(this)
                .setTitle("文件已存在")
                .setMessage("以下文件将被覆盖：\n\n$fileList\n\n是否继续？")
                .setPositiveButton("覆盖") { _, _ ->
                    doStartSplitting(interval, outputDir, displayPath, totalSegments, segmentDurations)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        
        doStartSplitting(interval, outputDir, displayPath, totalSegments, segmentDurations)
    }
    
    /**
     * 执行实际的分割操作
     */
    private fun doStartSplitting(
        interval: Int,
        outputDir: File,
        displayPath: String,
        totalSegments: Int,
        segmentDurations: List<Double>
    ) {
        val durationSec = videoDurationMs / 1000.0

        progressContainer.visibility = View.VISIBLE
        spinnerProgress.visibility = View.VISIBLE
        progressBar.max = 100
        progressBar.progress = 0
        tvProgressPercent.text = "正在分割 0%"
        tvProgressDetail.text = "准备中..."
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false
        
        val lastSegmentDuration = segmentDurations.lastOrNull() ?: interval.toDouble()
        val hasMergedSegment = lastSegmentDuration > interval
      
        tvStatus.text = if (hasMergedSegment) {
            "预计生成 $totalSegments 个片段\n(最后一段包含不足1秒的尾部)"
        } else {
            "预计生成 $totalSegments 个片段"
        }
        
        Log.i(TAG, "开始分割: 总时长=${durationSec}秒, 间隔=${interval}秒, 预计片段=$totalSegments")
        Log.i(TAG, "各段时长: $segmentDurations")
        Log.i(TAG, "输出目录: ${outputDir.absolutePath}")

        executor.execute {
            var successCount = 0
            var failedCount = 0
            val errorMessages = mutableListOf<String>()

            for (i in 0 until totalSegments) {
                val startTimeSec = i * interval
                val currentSegment = i + 1
                val segmentDuration = segmentDurations[i]
              
                val segmentNumber = String.format("%02d", currentSegment)
                val outputFile = File(outputDir, "${originalFileName}_${segmentNumber}.mp4").absolutePath

                val command = arrayOf(
                    "-ss", startTimeSec.toString(),
                    "-i", selectedVideoPath!!,
                    "-t", segmentDuration.toString(),
                    "-c:v", "libx264",
                    "-crf", "18",
                    "-preset", "fast",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-avoid_negative_ts", "make_zero",
                    "-y", outputFile
                )

                Log.d(TAG, "开始处理片段 $currentSegment: start=$startTimeSec, duration=$segmentDuration, output=$outputFile")

                val latch = CountDownLatch(1)
                var segmentSuccess = false
                var segmentError: String? = null
                val targetDurationMs = (segmentDuration * 1000).toLong()

                FFmpegKit.executeWithArgumentsAsync(
                    command,
                    { session ->
                        segmentSuccess = ReturnCode.isSuccess(session.returnCode)
                        
                        if (!segmentSuccess) {
                            segmentError = session.allLogsAsString
                            Log.e(TAG, "片段 $currentSegment 分割失败:")
                            Log.e(TAG, "返回码: ${session.returnCode}")
                            Log.e(TAG, "错误详情: $segmentError")
                        } else {
                            Log.i(TAG, "片段 $currentSegment 分割成功 (时长: ${String.format("%.1f", segmentDuration)}秒)")
                        }
                        
                        latch.countDown()
                    },
                    { log ->
                        Log.v(TAG, log.message)
                    },
                    { statistics ->
                        val timeMs = statistics.time
                        if (timeMs > 0) {
                            val segmentProgress = min((timeMs.toFloat() / targetDurationMs) * 100, 100f).toInt()
                            val overallProgress = (((i.toFloat() + segmentProgress / 100f) / totalSegments) * 100).toInt()
                            
                            // 【修复5】使用安全的 UI 更新
                            safeRunOnUiThread {
                                progressBar.progress = overallProgress
                                tvProgressPercent.text = "正在分割 $overallProgress%"
                                tvProgressDetail.text = "片段 $currentSegment/$totalSegments 编码中: $segmentProgress%"
                            }
                        }
                    }
                )

                try {
                    latch.await()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "等待片段完成时被中断", e)
                    Thread.currentThread().interrupt()
                    break
                }

                if (segmentSuccess) {
                    successCount++
                    // 【新增】立即通知相册刷新，让视频马上出现
                    MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(outputFile),
                        arrayOf("video/mp4"),
                        null
                    )
                } else {
                    failedCount++
                    errorMessages.add("片段$currentSegment: ${extractErrorSummary(segmentError)}")
                }

                val overallProgress = ((currentSegment.toFloat() / totalSegments) * 100).toInt()
                // 【修复5】使用安全的 UI 更新
                safeRunOnUiThread {
                    progressBar.progress = overallProgress
                    tvProgressPercent.text = "正在分割 $overallProgress%"
                    tvProgressDetail.text = "片段 $currentSegment/$totalSegments 完成"
                }
            }
            
            // 【修复2】分割完成后清理缓存
            cleanupCacheFiles()

            // 【修复5】使用安全的 UI 更新
            safeRunOnUiThread {
                progressBar.progress = 100
                tvProgressPercent.text = "分割完成 100%"
                tvProgressDetail.text = "处理完毕"
                spinnerProgress.visibility = View.GONE
              
                progressContainer.postDelayed({
                    // 【修复5】延迟回调也需要检查
                    if (!isActivityDestroyed && !isFinishing) {
                        progressContainer.visibility = View.GONE
                    }
                }, 2000)
              
                btnSplit.isEnabled = true
                btnSelectVideo.isEnabled = true

                if (failedCount == 0) {
                    val durationInfo = if (hasMergedSegment && successCount > 1) {
                        "前 ${successCount - 1} 段: 每段 $interval 秒\n" +
                        "最后 1 段: ${String.format("%.1f", lastSegmentDuration)} 秒"
                    } else if (successCount == 1) {
                        "时长: ${String.format("%.1f", lastSegmentDuration)} 秒"
                    } else {
                        "每段 $interval 秒"
                    }
                    
                    tvStatus.text = "✅ 分割完成！\n" +
                            "生成了 $successCount 个视频片段\n" +
                            "$durationInfo\n" +
                            "文件命名: ${originalFileName}_01 ~ ${originalFileName}_${String.format("%02d", successCount)}\n" +
                            "保存位置: $displayPath"
                    Log.i(TAG, "分割全部成功: $successCount 个片段")
                } else {
                    val errorSummary = if (errorMessages.size <= 3) {
                        errorMessages.joinToString("\n")
                    } else {
                        errorMessages.take(3).joinToString("\n") + "\n...等${errorMessages.size}个错误"
                    }
                    
                    tvStatus.text = "⚠️ 分割部分完成\n" +
                            "成功: $successCount 个\n" +
                            "失败: $failedCount 个\n" +
                            "保存位置: $displayPath\n\n" +
                            "错误信息:\n$errorSummary"
                    Log.w(TAG, "分割部分完成: 成功=$successCount, 失败=$failedCount")
                }
            }
        }
    }

    private fun extractErrorSummary(fullLog: String?): String {
        if (fullLog.isNullOrEmpty()) return "未知错误"
        
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
        
        val lastLine = fullLog.trim().lines().lastOrNull { it.isNotBlank() }
        return lastLine?.take(50) ?: "处理失败"
    }

    override fun onDestroy() {
        super.onDestroy()
        // 【修复5】标记 Activity 已销毁
        isActivityDestroyed = true
        executor.shutdown()
        // 【修复2】退出时清理缓存
        cleanupCacheFiles()
    }
}
