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
import androidx.lifecycle.lifecycleScope
import com.example.videosplitter.encoder.EncoderConfigFactory
import com.example.videosplitter.encoder.HardwareCodecDetector
import com.example.videosplitter.splitter.SmartVideoSplitter
import com.example.videosplitter.utils.VideoUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    // ==================== UI 组件 ====================
    private lateinit var btnSelectVideo: Button
    private lateinit var btnSplit: Button
    private lateinit var btnCancel: Button
    private lateinit var tvSelectedVideo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvEncoderInfo: TextView
    private lateinit var etInterval: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var progressContainer: LinearLayout
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvProgressDetail: TextView
    private lateinit var spinnerProgress: ProgressBar
    private lateinit var switchHardwareEncoder: Switch
    private lateinit var rgQuality: RadioGroup

    private lateinit var btn3s: Button
    private lateinit var btn4s: Button
    private lateinit var btn5s: Button
  
    // ==================== 数据 ====================
    private var selectedVideoPath: String? = null
    private var originalFileName: String = "video"
    private var videoInfo: VideoUtils.VideoInfo? = null
    
    // 智能分割器
    private lateinit var videoSplitter: SmartVideoSplitter
    
    // 当前分割任务
    private var splitJob: Job? = null

    companion object {
        private const val TAG = "VideoSplitter"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // ==================== Activity Result Launchers ====================
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

    // ==================== 生命周期 ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 初始化分割器
        videoSplitter = SmartVideoSplitter(this)
        
        initViews()
        setupClickListeners()
        checkAndRequestPermissions()
        detectHardwareEncoder()
    }

    override fun onDestroy() {
        super.onDestroy()
        splitJob?.cancel()
        VideoUtils.cleanupCache(this)
    }

    // ==================== 初始化 ====================
    private fun initViews() {
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnSplit = findViewById(R.id.btnSplit)
        btnCancel = findViewById(R.id.btnCancel)
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo)
        tvStatus = findViewById(R.id.tvStatus)
        tvEncoderInfo = findViewById(R.id.tvEncoderInfo)
        etInterval = findViewById(R.id.etInterval)
        progressBar = findViewById(R.id.progressBar)
        progressContainer = findViewById(R.id.progressContainer)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        spinnerProgress = findViewById(R.id.spinnerProgress)
        switchHardwareEncoder = findViewById(R.id.switchHardwareEncoder)
        rgQuality = findViewById(R.id.rgQuality)

        btn3s = findViewById(R.id.btn3s)
        btn4s = findViewById(R.id.btn4s)
        btn5s = findViewById(R.id.btn5s)
    }

    private fun setupClickListeners() {
        btnSelectVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }
        btnSplit.setOnClickListener { startSplitting() }
        btnCancel.setOnClickListener { cancelSplitting() }
      
        btn3s.setOnClickListener { etInterval.setText("3") }
        btn4s.setOnClickListener { etInterval.setText("4") }
        btn5s.setOnClickListener { etInterval.setText("5") }
        
        switchHardwareEncoder.setOnCheckedChangeListener { _, _ ->
            updateEncoderInfo()
        }
    }

    // ==================== 硬件编码器检测 ====================
    private fun detectHardwareEncoder() {
        val caps = HardwareCodecDetector.detectCapabilities()

        switchHardwareEncoder.isEnabled = caps.supportsH264
        switchHardwareEncoder.isChecked = caps.supportsH264

        updateEncoderInfo()

        if (!caps.supportsH264) {
            tvEncoderInfo.text = "⚠️ 设备不支持硬件加速\n将使用软件编码（速度较慢）"
        }
    }
    
    private fun updateEncoderInfo() {
        val useHardware = switchHardwareEncoder.isChecked
        val config = EncoderConfigFactory.getBestConfig(
            preferHardware = useHardware,
            videoWidth = videoInfo?.displaySize?.first ?: 1920,
            videoHeight = videoInfo?.displaySize?.second ?: 1080
        )

        tvEncoderInfo.text = buildString {
            append(if (config.isHardwareAccelerated) "🚀 " else "💻 ")
            append(config.description)
        }
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

    // ==================== 文件路径处理 ====================
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

    // ==================== 视频选择处理 ====================
    private fun handleVideoSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            tvSelectedVideo.text = "已选择: $fileName"
            originalFileName = fileName.substringBeforeLast(".")
            
            // 清理旧缓存
            VideoUtils.cleanupCache(this)
            
            // 智能获取视频路径
            val path = VideoUtils.getVideoPath(this, uri)
            if (path == null) {
                tvStatus.text = "❌ 无法读取视频文件"
                return
            }
            selectedVideoPath = path
            
            // 获取视频信息
            videoInfo = VideoUtils.getVideoInfo(path)
            if (videoInfo == null) {
                tvStatus.text = "❌ 无法解析视频信息"
                return
            }
            
            // 更新编码器信息
            updateEncoderInfo()
            
            btnSplit.isEnabled = true
            
            val info = videoInfo!!
            tvStatus.text = buildString {
                appendLine("✅ 视频已准备好")
                appendLine("时长: ${info.durationFormatted} | 分辨率: ${info.resolution}")
                appendLine("请设置分割间隔后点击分割")
            }
            
            Log.i(TAG, "视频信息: $info")
          
        } catch (e: Exception) {
            tvStatus.text = "选择视频失败: ${e.message}"
            Log.e(TAG, "视频选择失败", e)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "未知文件"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    // ==================== 分割功能 ====================
    private fun startSplitting() {
        // 参数验证
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
        
        val path = selectedVideoPath
        if (path == null) {
            tvStatus.text = "请先选择视频"
            return
        }
        
        val info = videoInfo
        if (info == null) {
            tvStatus.text = "视频信息无效"
            return
        }
        
        if (interval > info.durationSeconds) {
            tvStatus.text = "⚠️ 分割间隔超过视频时长"
            return
        }
        
        // 准备配置（并行处理始终开启）
        val config = SmartVideoSplitter.SplitConfig(
            inputPath = path,
            outputDir = getOutputDirectory(),
            outputNamePrefix = originalFileName,
            intervalSeconds = interval,
            videoDurationMs = info.durationMs,
            videoWidth = info.displaySize.first,
            videoHeight = info.displaySize.second,
            useHardwareEncoder = switchHardwareEncoder.isChecked,
            enableParallel = true,
            qualityPreset = getSelectedQualityPreset()
        )
        
        // 更新 UI 状态
        setProcessingState(true)

        val encoderInfo = if (switchHardwareEncoder.isChecked) "🚀 硬件加速" else "💻 软件编码"
        val qualityInfo = when (getSelectedQualityPreset()) {
            EncoderConfigFactory.QualityPreset.FAST -> "快速"
            EncoderConfigFactory.QualityPreset.BALANCED -> "平衡"
            EncoderConfigFactory.QualityPreset.QUALITY -> "高质量"
        }
        tvStatus.text = "开始分割...\n$encoderInfo | 质量: $qualityInfo"
        
        // 启动分割任务
        splitJob = lifecycleScope.launch {
            try {
                val result = videoSplitter.split(config) { progress ->
                    // 更新进度
                    progressBar.progress = progress.overallProgress
                    tvProgressPercent.text = "正在分割 ${progress.overallProgress}%"
                    tvProgressDetail.text = progress.status
                }

                // 分割完成，立即更新 UI
                progressContainer.visibility = View.GONE
                btnSplit.isEnabled = true
                btnSelectVideo.isEnabled = true
                switchHardwareEncoder.isEnabled = true
                rgQuality.isEnabled = true
                for (i in 0 until rgQuality.childCount) {
                    rgQuality.getChildAt(i).isEnabled = true
                }
                btnCancel.visibility = View.GONE
                spinnerProgress.visibility = View.GONE
                showResult(result)

            } catch (e: kotlinx.coroutines.CancellationException) {
                progressContainer.visibility = View.GONE
                setProcessingState(false)
                tvStatus.text = "❌ 已取消分割"
            } catch (e: Exception) {
                progressContainer.visibility = View.GONE
                setProcessingState(false)
                tvStatus.text = "❌ 分割失败: ${e.message}"
                Log.e(TAG, "分割失败", e)
            }
        }
    }
    
    private fun cancelSplitting() {
        splitJob?.cancel()
        tvStatus.text = "正在取消..."
    }

    private fun getSelectedQualityPreset(): EncoderConfigFactory.QualityPreset {
        return when (rgQuality.checkedRadioButtonId) {
            R.id.rbFast -> EncoderConfigFactory.QualityPreset.FAST
            R.id.rbQuality -> EncoderConfigFactory.QualityPreset.QUALITY
            else -> EncoderConfigFactory.QualityPreset.BALANCED
        }
    }

    private fun setProcessingState(isProcessing: Boolean) {
        btnSplit.isEnabled = !isProcessing
        btnSelectVideo.isEnabled = !isProcessing
        switchHardwareEncoder.isEnabled = !isProcessing
        rgQuality.isEnabled = !isProcessing
        for (i in 0 until rgQuality.childCount) {
            rgQuality.getChildAt(i).isEnabled = !isProcessing
        }

        btnCancel.visibility = if (isProcessing) View.VISIBLE else View.GONE
        progressContainer.visibility = if (isProcessing) View.VISIBLE else View.GONE
        spinnerProgress.visibility = if (isProcessing) View.VISIBLE else View.GONE

        if (isProcessing) {
            progressBar.progress = 0
            tvProgressPercent.text = "准备中..."
            tvProgressDetail.text = ""
        }
    }
    
    private fun showResult(result: SmartVideoSplitter.SplitResult) {
        val displayPath = getOutputDisplayPath()
        val durationSec = result.totalDurationMs / 1000.0

        val encoderInfo = if (result.usedHardwareAcceleration) {
            "🚀 硬件加速"
        } else {
            "💻 软件编码"
        }

        if (result.success) {
            tvStatus.text = buildString {
                appendLine("✅ 分割完成！")
                appendLine("生成了 ${result.outputFiles.size} 个视频片段")
                appendLine("耗时: ${String.format("%.1f", durationSec)} 秒 | $encoderInfo")
                appendLine("保存位置: $displayPath")
            }
        } else {
            tvStatus.text = buildString {
                appendLine("⚠️ 分割部分完成")
                appendLine("成功: ${result.outputFiles.size} 个")
                appendLine("失败: ${result.failedSegments.size} 个")
                appendLine("保存位置: $displayPath")

                // 显示详细的失败原因（只显示第一个失败片段的详情，避免太长）
                if (result.failedDetails.isNotEmpty()) {
                    val firstFailed = result.failedDetails.first()
                    appendLine()
                    appendLine("❌ 失败详情 (片段${firstFailed.segmentIndex}):")
                    appendLine("原因: ${firstFailed.errorReason}")

                    // 显示 FFmpeg 命令
                    firstFailed.ffmpegCommand?.let { cmd ->
                        appendLine()
                        appendLine("📋 FFmpeg 命令:")
                        appendLine(cmd)
                    }

                    // 显示错误日志
                    firstFailed.fullErrorLog?.let { log ->
                        if (log.isNotBlank()) {
                            appendLine()
                            appendLine("📝 错误日志:")
                            appendLine(log)
                        }
                    }

                    // 如果有多个失败，提示还有其他
                    if (result.failedDetails.size > 1) {
                        appendLine()
                        appendLine("(还有 ${result.failedDetails.size - 1} 个片段失败，原因类似)")
                    }
                }
            }
        }
    }
}
