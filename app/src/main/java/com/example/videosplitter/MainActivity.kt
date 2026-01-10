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
import com.arthenica.ffmpegkit.FFmpegKitConfig
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
    }

    private fun setupClickListeners() {
        btnSelectVideo.setOnClickListener { videoPickerLauncher.launch("video/*") }
        btnSplit.setOnClickListener { startSplitting() }
    }

    private fun handleVideoSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            tvSelectedVideo.text = "已选择: $fileName"
            
            // 复制视频到缓存目录
            val inputFile = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }
            selectedVideoPath = inputFile.absolutePath
            
            // 获取视频时长（用于进度计算）
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

        val timestamp = System.currentTimeMillis()
        val outputPattern = File(outputDir, "split_${timestamp}_%03d.mp4").absolutePath

        // 更新 UI 状态
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false
        tvStatus.text = "正在分割视频，请稍候...\n(重编码以确保精准时间，可能需要几分钟)"

        // ========== 关键修改：使用重编码实现精准切割 ==========
        val command = "-i \"$selectedVideoPath\" " +
                "-c:v libx264 -crf 18 -preset fast " +
                "-c:a aac -b:a 192k " +
                "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " +
                "-f segment -segment_time $interval " +
                "-reset_timestamps 1 " +
                "-y \"$outputPattern\""

        // 设置进度回调
        FFmpegKitConfig.enableStatisticsCallback { statistics ->
            if (videoDurationMs > 0) {
                // ========== 修复：显式转换为 Long ==========
                val timeMs = statistics.time.toLong()
                val progress = ((timeMs.toDouble() / videoDurationMs.toDouble()) * 100).toInt().coerceIn(0, 100)
                runOnUiThread {
                    progressBar.progress = progress
                    tvStatus.text = "正在分割视频... $progress%\n已处理: ${formatDuration(timeMs)}"
                }
            }
        }

        // 在后台线程执行 FFmpeg
        executor.execute {
            val session = FFmpegKit.execute(command)
            
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnSplit.isEnabled = true
                btnSelectVideo.isEnabled = true
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    // 统计生成的文件数量
                    val count = outputDir.listFiles { f -> 
                        f.name.startsWith("split_$timestamp") 
                    }?.size ?: 0
                    
                    tvStatus.text = "✅ 分割完成！\n" +
                            "生成了 $count 个视频片段\n" +
                            "每段精准 $interval 秒\n" +
                            "保存位置: Movies/VideoSplitter"
                } else {
                    // 获取错误信息
                    val errorLog = session.failStackTrace ?: "未知错误"
                    tvStatus.text = "❌ 分割失败\n错误码: ${session.returnCode}\n$errorLog"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
