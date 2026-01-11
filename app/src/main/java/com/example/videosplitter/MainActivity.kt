package com.example.videosplitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
        private const val TAG = "VideoSplitter"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleVideoSelection(it) }
    }

    // Android 11+ 存储管理权限请求
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

    /**
     * 检查是否有存储权限
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 检查是否有管理所有文件权限
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 检查写入权限
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 检查并请求权限
     */
    private fun checkAndRequestPermissions() {
        if (hasStoragePermission()) {
            tvStatus.text = "请选择视频文件"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要特殊权限
            showStoragePermissionDialog()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 请求普通存储权限
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

    /**
     * 显示存储权限说明对话框（Android 11+）
     */
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
                    // 某些设备可能不支持直接跳转
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

    /**
     * 权限请求结果回调（Android 6-10）
     */
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

    /**
     * 获取输出目录（根据权限情况选择）
     */
    private fun getOutputDirectory(): File {
        val outputDir = if (hasStoragePermission()) {
            // 有权限：保存到公共 Movies 目录
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "VideoSplitter"
            )
        } else {
            // 无权限：保存到应用私有目录（不需要权限）
            File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoSplitter")
        }
        
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        return outputDir
    }

    /**
     * 获取输出目录的显示路径
     */
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

        // 使用权限感知的输出目录
        val outputDir = getOutputDirectory()
        val displayPath = getOutputDisplayPath()

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
        Log.i(TAG, "输出目录: ${outputDir.absolutePath}")

        executor.execute {
            var successCount = 0
            var failedCount = 0
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

                val latch = CountDownLatch(1)
                var segmentSuccess = false
                var segmentError: String? = null
                val targetDurationMs = interval * 1000L

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
                            Log.i(TAG, "片段 $currentSegment 分割成功")
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
                            
                            runOnUiThread {
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
                } else {
                    failedCount++
                    errorMessages.add("片段$currentSegment: ${extractErrorSummary(segmentError)}")
                }

                val overallProgress = ((currentSegment.toFloat() / totalSegments) * 100).toInt()
                runOnUiThread {
                    progressBar.progress = overallProgress
                    tvProgressPercent.text = "正在分割 $overallProgress%"
                    tvProgressDetail.text = "片段 $currentSegment/$totalSegments 完成"
                }
            }

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
        executor.shutdown()
    }
}
