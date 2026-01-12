package com.example.videosplitter.splitter

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.videosplitter.encoder.EncoderConfig
import com.example.videosplitter.encoder.EncoderConfigFactory
import com.example.videosplitter.encoder.HardwareCodecDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 智能视频分割器
 * 支持硬件加速、自动回退、并行处理、取消操作
 */
class SmartVideoSplitter(
    private val context: Context
) {
    private val TAG = "SmartVideoSplitter"
    
    // 当前编码配置
    private var currentConfig: EncoderConfig? = null
    
    // 硬件编码失败计数
    private var hardwareFailCount = 0
    private val maxHardwareFailures = 2
    
    // 并行数（CPU 核心数的一半，至少2个）
    private val parallelCount: Int by lazy {
        (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    }
    
    /**
     * 分割结果
     */
    data class SplitResult(
        val success: Boolean,
        val outputFiles: List<File>,
        val failedSegments: List<Int>,
        val totalDurationMs: Long,
        val usedHardwareAcceleration: Boolean,
        val errorMessage: String? = null
    )
    
    /**
     * 单个片段的结果
     */
    data class SegmentResult(
        val index: Int,
        val success: Boolean,
        val outputFile: File?,
        val error: String? = null
    )
    
    /**
     * 进度回调
     */
    data class Progress(
        val currentSegment: Int,
        val totalSegments: Int,
        val segmentProgress: Int,      // 当前片段进度 0-100
        val overallProgress: Int,      // 总体进度 0-100
        val status: String
    )
    
    /**
     * 分割配置
     */
    data class SplitConfig(
        val inputPath: String,
        val outputDir: File,
        val outputNamePrefix: String,
        val intervalSeconds: Int,
        val videoDurationMs: Long,
        val videoWidth: Int,
        val videoHeight: Int,
        val useHardwareEncoder: Boolean = true,
        val enableParallel: Boolean = false,    // 是否启用并行处理
        val qualityPreset: EncoderConfigFactory.QualityPreset = EncoderConfigFactory.QualityPreset.BALANCED
    )
    
    /**
     * 开始分割视频
     */
    suspend fun split(
        config: SplitConfig,
        onProgress: (Progress) -> Unit
    ): SplitResult = withContext(Dispatchers.IO) {
        
        Log.i(TAG, "开始分割视频: ${config.inputPath}")
        Log.i(TAG, "输出目录: ${config.outputDir}")
        Log.i(TAG, "间隔: ${config.intervalSeconds}秒")
        Log.i(TAG, "硬件加速: ${config.useHardwareEncoder}")
        Log.i(TAG, "并行处理: ${config.enableParallel}")
        
        val startTime = System.currentTimeMillis()
        
        // 初始化编码器配置
        currentConfig = EncoderConfigFactory.getBestConfig(
            preferHardware = config.useHardwareEncoder,
            videoWidth = config.videoWidth,
            videoHeight = config.videoHeight,
            qualityPreset = config.qualityPreset
        )
        hardwareFailCount = 0
        
        Log.i(TAG, "使用编码器: ${currentConfig!!.description}")
        
        // 计算分段信息
        val segments = calculateSegments(
            durationMs = config.videoDurationMs,
            intervalSeconds = config.intervalSeconds
        )
                // 计算分段信息
        val segments = calculateSegments(
            durationMs = config.videoDurationMs,
            intervalSeconds = config.intervalSeconds
        )
        
        Log.i(TAG, "预计生成 ${segments.size} 个片段")
        
        // 确保输出目录存在
        if (!config.outputDir.exists()) {
            config.outputDir.mkdirs()
        }
        
        // 执行分割
        val results = if (config.enableParallel && segments.size > 1) {
            splitParallel(config, segments, onProgress)
        } else {
            splitSequential(config, segments, onProgress)
        }
        
        // 统计结果
        val successFiles = results.filter { it.success }.mapNotNull { it.outputFile }
        val failedIndices = results.filter { !it.success }.map { it.index }
        
        val totalDuration = System.currentTimeMillis() - startTime
        
        // 刷新媒体库
        if (successFiles.isNotEmpty()) {
            MediaScannerConnection.scanFile(
                context,
                successFiles.map { it.absolutePath }.toTypedArray(),
                Array(successFiles.size) { "video/mp4" },
                null
            )
        }
        
        val result = SplitResult(
            success = failedIndices.isEmpty(),
            outputFiles = successFiles,
            failedSegments = failedIndices,
            totalDurationMs = totalDuration,
            usedHardwareAcceleration = currentConfig?.isHardwareAccelerated == true,
            errorMessage = if (failedIndices.isNotEmpty()) {
                "片段 ${failedIndices.joinToString(", ")} 处理失败"
            } else null
        )
        
        Log.i(TAG, "分割完成: 成功=${successFiles.size}, 失败=${failedIndices.size}, 耗时=${totalDuration}ms")
        
        result
    }
    
    /**
     * 顺序分割（一个接一个）
     */
    private suspend fun splitSequential(
        config: SplitConfig,
        segments: List<SegmentInfo>,
        onProgress: (Progress) -> Unit
    ): List<SegmentResult> {
        
        val results = mutableListOf<SegmentResult>()
        
        for ((index, segment) in segments.withIndex()) {
            // 检查是否取消
            ensureActive()
            
            // 更新进度
            withContext(Dispatchers.Main) {
                onProgress(Progress(
                    currentSegment = index + 1,
                    totalSegments = segments.size,
                    segmentProgress = 0,
                    overallProgress = (index * 100) / segments.size,
                    status = "正在处理片段 ${index + 1}/${segments.size}"
                ))
            }
            
            // 处理片段
            val result = processSegment(
                config = config,
                segment = segment,
                index = index,
                totalSegments = segments.size,
                onSegmentProgress = { segmentProgress ->
                    val overall = ((index + segmentProgress / 100f) / segments.size * 100).toInt()
                    onProgress(Progress(
                        currentSegment = index + 1,
                        totalSegments = segments.size,
                        segmentProgress = segmentProgress,
                        overallProgress = overall,
                        status = "片段 ${index + 1}/${segments.size} 编码中: $segmentProgress%"
                    ))
                }
            )
            
            results.add(result)
            
            // 如果失败且是硬件编码，考虑回退
            if (!result.success && currentConfig?.isHardwareAccelerated == true) {
                hardwareFailCount++
                if (hardwareFailCount >= maxHardwareFailures) {
                    Log.w(TAG, "硬件编码连续失败，切换到软件编码")
                    currentConfig = EncoderConfigFactory.getSoftwareConfig(config.qualityPreset)
                }
            } else if (result.success) {
                hardwareFailCount = 0
            }
        }
        
        return results
    }
    
    /**
     * 并行分割（同时处理多个）
     */
    private suspend fun splitParallel(
        config: SplitConfig,
        segments: List<SegmentInfo>,
        onProgress: (Progress) -> Unit
    ): List<SegmentResult> = coroutineScope {
        
        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(parallelCount)
        
        Log.i(TAG, "并行处理: 最大并发数=$parallelCount")
        
        val deferredResults = segments.mapIndexed { index, segment ->
            async {
                semaphore.withPermit {
                    val result = processSegment(
                        config = config,
                        segment = segment,
                        index = index,
                        totalSegments = segments.size,
                        onSegmentProgress = { /* 并行模式下不更新单个进度 */ }
                    )
                    
                    // 更新总进度
                    val completed = completedCount.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        onProgress(Progress(
                            currentSegment = completed,
                            totalSegments = segments.size,
                            segmentProgress = 100,
                            overallProgress = (completed * 100) / segments.size,
                            status = "已完成 $completed/${segments.size}"
                        ))
                    }
                    
                    result
                }
            }
        }
        
        deferredResults.awaitAll()
    }
    
    /**
     * 处理单个片段
     */
    private suspend fun processSegment(
        config: SplitConfig,
        segment: SegmentInfo,
        index: Int,
        totalSegments: Int,
        onSegmentProgress: (Int) -> Unit
    ): SegmentResult = suspendCancellableCoroutine { continuation ->
        
        val segmentNumber = String.format("%02d", index + 1)
        val outputFile = File(config.outputDir, "${config.outputNamePrefix}_${segmentNumber}.mp4")
        
        val encoderConfig = currentConfig ?: EncoderConfig.DEFAULT_SOFTWARE
        
        // 构建 FFmpeg 命令
        val command = buildFFmpegCommand(
            inputPath = config.inputPath,
            startTimeSec = segment.startTimeSec,
            durationSec = segment.durationSec,
            outputPath = outputFile.absolutePath,
            encoderConfig = encoderConfig
        )
        
        Log.d(TAG, "片段 ${index + 1}: ${command.joinToString(" ")}")
        
        val targetDurationMs = (segment.durationSec * 1000).toLong()
        
        val session = FFmpegKit.executeWithArgumentsAsync(
            command,
            { session ->
                val success = ReturnCode.isSuccess(session.returnCode)
                
                if (success) {
                    Log.i(TAG, "片段 ${index + 1} 完成: ${outputFile.name}")
                    continuation.resume(SegmentResult(
                        index = index,
                        success = true,
                        outputFile = outputFile
                    ))
                } else {
                    val error = session.allLogsAsString
                    Log.e(TAG, "片段 ${index + 1} 失败: $error")
                    continuation.resume(SegmentResult(
                        index = index,
                        success = false,
                        outputFile = null,
                        error = extractErrorSummary(error)
                    ))
                }
            },
            { log ->
                Log.v(TAG, log.message)
            },
            { statistics ->
                val timeMs = statistics.time
                if (timeMs > 0 && targetDurationMs > 0) {
                    val progress = ((timeMs.toFloat() / targetDurationMs) * 100)
                        .toInt()
                        .coerceIn(0, 100)
                    onSegmentProgress(progress)
                }
            }
        )
        
        // 支持取消
        continuation.invokeOnCancellation {
            Log.w(TAG, "片段 ${index + 1} 被取消")
            session.cancel()
        }
    }
    
    /**
     * 构建 FFmpeg 命令
     */
    private fun buildFFmpegCommand(
        inputPath: String,
        startTimeSec: Double,
        durationSec: Double,
        outputPath: String,
        encoderConfig: EncoderConfig
    ): Array<String> {
        return mutableListOf<String>().apply {
            // 输入参数
            addAll(listOf("-ss", startTimeSec.toString()))
            addAll(listOf("-i", inputPath))
            addAll(listOf("-t", durationSec.toString()))
            
            // 编码参数
            addAll(encoderConfig.buildOutputParams())
            
            // 输出
            addAll(listOf("-y", outputPath))
        }.toTypedArray()
    }
    
    /**
     * 计算分段信息
     */
    private fun calculateSegments(durationMs: Long, intervalSeconds: Int): List<SegmentInfo> {
        val durationSec = durationMs / 1000.0
        val segments = mutableListOf<SegmentInfo>()
        
        var currentTime = 0.0
        var index = 0
        
        while (currentTime < durationSec) {
            val remaining = durationSec - currentTime
            val segmentDuration = if (remaining < intervalSeconds) {
                // 最后一段
                if (remaining < 1.0 && segments.isNotEmpty()) {
                    // 不足1秒，合并到上一段
                    val lastSegment = segments.removeAt(segments.lastIndex)
                    segments.add(lastSegment.copy(
                        durationSec = lastSegment.durationSec + remaining
                    ))
                    break
                } else {
                    remaining
                }
            } else {
                intervalSeconds.toDouble()
            }
            
            segments.add(SegmentInfo(
                index = index,
                startTimeSec = currentTime,
                durationSec = segmentDuration
            ))
            
            currentTime += intervalSeconds
            index++
        }
        
        return segments
    }
    
    /**
     * 提取错误摘要
     */
    private fun extractErrorSummary(fullLog: String?): String {
        if (fullLog.isNullOrEmpty()) return "未知错误"
        
        val errorPatterns = listOf(
            "No such file" to "文件不存在",
            "Permission denied" to "权限被拒绝",
            "Invalid data" to "无效数据",
            "Encoder not found" to "编码器未找到",
            "Out of memory" to "内存不足",
            "No space left" to "存储空间不足",
            "mediacodec" to "硬件编码器错误",
            "Invalid argument" to "参数无效"
        )
        
        for ((pattern, message) in errorPatterns) {
            if (fullLog.contains(pattern, ignoreCase = true)) {
                return message
            }
        }
        
        return fullLog.trim().lines().lastOrNull { it.isNotBlank() }?.take(50) ?: "处理失败"
    }
    
    /**
     * 片段信息
     */
    private data class SegmentInfo(
        val index: Int,
        val startTimeSec: Double,
        val durationSec: Double
    )
    
    /**
     * 获取当前编码器信息
     */
    fun getCurrentEncoderInfo(): String {
        return currentConfig?.description ?: "未初始化"
    }
    
    /**
     * 检查硬件加速是否可用
     */
    fun isHardwareAccelerationAvailable(): Boolean {
        return HardwareCodecDetector.isHardwareEncodingAvailable()
    }
}
