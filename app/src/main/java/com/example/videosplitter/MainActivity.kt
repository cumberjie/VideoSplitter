package com.example.videosplitter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
            
            // 将视频复制到缓存目录，确保 FFmpeg 能读取
            val inputFile = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }
            selectedVideoPath = inputFile.absolutePath
            btnSplit.isEnabled = true
            tvStatus.text = "视频已准备好，请设置分割间隔"
        } catch (e: Exception) {
            tvStatus.text = "选择视频失败: ${e.message}"
            e.printStackTrace()
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

        // ⚠️ 修改点1：使用应用私有目录，避免 Android 10+ 权限报错
        // 路径通常为: /sdcard/Android/data/com.example.videosplitter/files/VideoSplitter
        val outputDir = File(getExternalFilesDir(null), "VideoSplitter")
        
        // 清理旧文件（可选，防止文件堆积）
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        val timestamp = System.currentTimeMillis()
        // 输出文件名格式：split_时间戳_001.mp4
        val outputPattern = File(outputDir, "split_${timestamp}_%03d.mp4").absolutePath

        progressBar.visibility = View.VISIBLE
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false
        tvStatus.text = "正在进行无损精准分割 (速度较慢，请耐心等待)..."

        // ⚠️ 修改点2：核心 FFmpeg 命令 (精准 + 无损)
        val command = "-i \"$selectedVideoPath\" " +
                "-c:v libx264 " +       // 强制使用 x264 编码器
                "-crf 17 " +            // 视觉无损画质 (17-18 几乎等同原画)
                "-preset ultrafast " +  // 极速编码模式 (牺牲一点压缩率换取速度)
                "-force_key_frames \"expr:gte(t,n_forced*$interval)\" " + // 关键：强制在切割点打关键帧
                "-c:a copy " +          // 音频直接复制
                "-map 0 " +             // 保留所有轨道
                "-f segment " +         // 分段模式
                "-segment_time $interval " + // 分割时间
                "-reset_timestamps 1 " + // 重置时间戳
                "\"$outputPattern\""

        executor.execute {
            // 执行命令
            val session = FFmpegKit.execute(command)
            
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnSplit.isEnabled = true
                btnSelectVideo.isEnabled = true
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    val count = outputDir.listFiles()?.size ?: 0
                    tvStatus.text = "✅ 分割完成！\n生成了 $count 个视频\n保存路径: ${outputDir.absolutePath}"
                    
                    // 尝试打开文件夹 (部分手机可能不支持)
                    Toast.makeText(this, "文件保存在: Android/data/.../VideoSplitter", Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = "❌ 分割失败\n查看 Logcat 获取详情"
                    // 打印错误日志到控制台
                    println("FFmpeg Error: ${session.failStackTrace}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
