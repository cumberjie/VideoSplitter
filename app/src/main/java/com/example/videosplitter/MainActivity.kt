package com.example.videosplitter

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
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

            // 拷贝到 App 缓存，FFmpegKit 直接读本地路径最稳
            val inputFile = File(cacheDir, "input_video.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }

            selectedVideoPath = inputFile.absolutePath
            btnSplit.isEnabled = true
            tvStatus.text = "视频已准备好，请设置分割间隔（秒）"
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
        val intervalText = etInterval.text.toString().trim()
        if (intervalText.isEmpty()) {
            tvStatus.text = "请输入分割间隔秒数"
            return
        }

        val interval = intervalText.toLongOrNull()
        if (interval == null || interval <= 0) {
            tvStatus.text = "请输入有效的秒数（>0）"
            return
        }

        val inputPath = selectedVideoPath
        if (inputPath.isNullOrBlank()) {
            tvStatus.text = "请先选择视频"
            return
        }

        // ✅ Android 10+ 推荐：写到 App 专属 Movies 目录（无需额外存储权限）
        val outputDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "VideoSplitter")
        if (!outputDir.exists()) outputDir.mkdirs()

        val timestamp = System.currentTimeMillis()

        progressBar.visibility = View.VISIBLE
        btnSplit.isEnabled = false
        btnSelectVideo.isEnabled = false
        tvStatus.text = "正在分割视频，请稍候..."

        executor.execute {
            try {
                // 1) 先用 ffprobe 拿到总时长（秒，可能小数）
                val durationSec = probeDurationSeconds(inputPath)

                if (durationSec <= 0.0) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnSplit.isEnabled = true
                        btnSelectVideo.isEnabled = true
                        tvStatus.text = "获取视频时长失败，无法分割"
                    }
                    return@execute
                }

                // 2) 按时间循环切：-ss（起点） + -t（时长），并重编码保证切点精准
                val totalSegments = kotlin.math.ceil(durationSec / interval.toDouble()).toInt().coerceAtLeast(1)
                var successCount = 0

                for (i in 0 until totalSegments) {
                    val start = i * interval
                    val remaining = durationSec - start.toDouble()
                    if (remaining <= 0) break

                    val segmentLen = kotlin.math.min(interval.toDouble(), remaining)
                    val outName = String.format("split_%d_%03d.mp4", timestamp, i + 1)
                    val outPath = File(outputDir, outName).absolutePath

                    // ✅ 关键点：
                    // -ss 放在 -i 后面：更精确（但会慢一点）
                    // -c copy 改为重编码：解决关键帧导致的 9/10/1 漂移问题
                    // 视频：libx264 + CRF 18（质量高、体积通常可接受）
                    // 音频：AAC 128k（避免体积爆炸）
                    val cmd = buildString {
                        append("-hide_banner -y ")
                        append("-i \"").append(inputPath).append("\" ")
                        append("-ss ").append(start).append(" ")
                        append("-t ").append(segmentLen).append(" ")
                        append("-map 0:v:0? -map 0:a:0? ")
                        append("-c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p ")
                        append("-c:a aac -b:a 128k ")
                        append("-movflags +faststart ")
                        append("\"").append(outPath).append("\"")
                    }

                    val session = FFmpegKit.execute(cmd)
                    if (!ReturnCode.isSuccess(session.returnCode)) {
                        // 失败就停止并报错（方便你定位）
                        val rc = session.returnCode
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            btnSplit.isEnabled = true
                            btnSelectVideo.isEnabled = true
                            tvStatus.text = "分割失败：第 ${i + 1} 段（错误码: $rc）"
                        }
                        return@execute
                    } else {
                        successCount++
                        runOnUiThread {
                            tvStatus.text = "正在分割：$successCount / $totalSegments"
                        }
                    }
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnSplit.isEnabled = true
                    btnSelectVideo.isEnabled = true
                    tvStatus.text = "分割完成！生成 $successCount 段\n保存位置：${outputDir.absolutePath}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnSplit.isEnabled = true
                    btnSelectVideo.isEnabled = true
                    tvStatus.text = "分割异常：${e.message}"
                }
            }
        }
    }

    /**
     * 用 ffprobe 获取视频总时长（秒）
     * 返回 <=0 表示失败
     */
    private fun probeDurationSeconds(inputPath: String): Double {
        val cmd = "-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"$inputPath\""
        val session = FFprobeKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) return -1.0

        val out = (session.output ?: "").trim()
        return out.toDoubleOrNull() ?: -1.0
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
