package com.example.videosplitter.splitter

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.videosplitter.encoder.EncoderConfig
import com.example.videosplitter.encoder.EncoderConfigFactory
import com.example.videosplitter.encoder.HardwareCodecDetector
import com.example.videosplitter.encoder.MediaCodecSplitter
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
    private val maxHardwareFailures = 1  // 第一次失败就切换，提高兼容性

    // 纯 MediaCodec 硬件分割器
    private val mediaCodecSplitter = MediaCodecSplitter()
    
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
        val errorMessage: String? = null,
        val failedDetails: List<FailedSegmentInfo> = emptyList()  // 失败片段的详细信息
    )

    /**
     * 失败片段的详细信息
     */
    data class FailedSegmentInfo(
        val segmentIndex: Int,
        val errorReason: String,
        val ffmpegCommand: String? = null,  // FFmpeg 命令
        val fullErrorLog: String? = null     // 完整错误日志（截取关键部分）
    )

    /**
     * 单个片段的结果
     */
    data class SegmentResult(
        val index: Int,
        val success: Boolean,
        val outputFile: File?,
        val error: String? = null,
        val ffmpegCommand: String? = null,
        val fullErrorLog: String? = null
    )
    
    /**
     * 进度回调
     */
    data class Progress(
        val currentSegment: Int,
        val totalSegments: Int,
        val segmentProgress: Int,
        val overallProgress: Int,
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
        val enableParallel: Boolean = false,
        val qualityPreset: EncoderConfigFactory.QualityPreset = EncoderConfigFactory.QualityPreset.BALANCED
    )
    
    /**
     * 片段信息
     */
    private data class SegmentInfo(
        val index: Int,
        val startTimeSec: Double,
        val durationSec: Double
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
        val segmentList = calculateSegments(
            durationMs = config.videoDurationMs,
            intervalSeconds = config.intervalSeconds
        )
        
        Log.i(TAG, "预计生成 ${segmentList.size} 个片段")
        
        // 确保输出目录存在
        if (!config.outputDir.exists()) {
            config.outputDir.mkdirs()
        }
        
        // 执行分割
        val results = if (config.enableParallel && segmentList.size > 1) {
            splitParallel(config, segmentList, onProgress)
        } else {
            splitSequential(config, segmentList, onProgress)
        }
        
        // 统计结果
        val successFiles = results.filter { it.success }.mapNotNull { it.outputFile }
        val failedIndices = results.filter { !it.success }.map { it.index }

        // 收集失败片段的详细信息（包含 FFmpeg 命令和错误日志）
        val failedDetails = results.filter { !it.success }.map { segmentResult ->
            FailedSegmentInfo(
                segmentIndex = segmentResult.index + 1,
                errorReason = segmentResult.error ?: "未知错误",
                ffmpegCommand = segmentResult.ffmpegCommand,
                fullErrorLog = segmentResult.fullErrorLog
            )
        }

        // 修正文件时间戳，确保相册按顺序显示
        successFiles.sortedBy { it.name }.forEachIndexed { index, file ->
            val timestamp = System.currentTimeMillis() + (index * 1000L)
            file.setLastModified(timestamp)
        }

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
                "片段 ${failedIndices.map { it + 1 }.joinToString(", ")} 处理失败"
            } else null,
            failedDetails = failedDetails
        )
        
        Log.i(TAG, "分割完成: 成功=${successFiles.size}, 失败=${failedIndices.size}, 耗时=${totalDuration}ms")
        
        result
    }
    
    /**
     * 顺序分割
     */
    private suspend fun splitSequential(
        config: SplitConfig,
        segmentList: List<SegmentInfo>,
        onProgress: (Progress) -> Unit
    ): List<SegmentResult> = coroutineScope {

        val results = mutableListOf<SegmentResult>()
        // 记录因硬件编码失败需要重试的片段
        val failedSegmentsToRetry = mutableListOf<Pair<Int, SegmentInfo>>()

        for ((index, segment) in segmentList.withIndex()) {
            // 检查是否取消
            if (!isActive) break

            // 更新进度
            withContext(Dispatchers.Main) {
                onProgress(Progress(
                    currentSegment = index + 1,
                    totalSegments = segmentList.size,
                    segmentProgress = 0,
                    overallProgress = (index * 100) / segmentList.size,
                    status = "正在处理片段 ${index + 1}/${segmentList.size}"
                ))
            }

            // 处理片段
            val result = processSegment(
                config = config,
                segment = segment,
                index = index,
                totalSegments = segmentList.size,
                onSegmentProgress = { segmentProgress ->
                    val overall = ((index + segmentProgress / 100f) / segmentList.size * 100).toInt()
                    onProgress(Progress(
                        currentSegment = index + 1,
                        totalSegments = segmentList.size,
                        segmentProgress = segmentProgress,
                        overallProgress = overall,
                        status = "片段 ${index + 1}/${segmentList.size} 编码中: $segmentProgress%"
                    ))
                }
            )

            results.add(result)

            // 如果失败且是硬件编码，考虑回退
            if (!result.success && currentConfig?.isHardwareAccelerated == true) {
                hardwareFailCount++
                // 记录失败的片段，稍后用软件编码重试
                failedSegmentsToRetry.add(Pair(index, segment))

                if (hardwareFailCount >= maxHardwareFailures) {
                    Log.w(TAG, "硬件编码连续失败，切换到软件编码")
                    currentConfig = EncoderConfigFactory.getSoftwareConfig(config.qualityPreset)
                }
            } else if (result.success) {
                hardwareFailCount = 0
            }
        }

        // 如果切换到了软件编码，重试之前硬件编码失败的片段
        if (failedSegmentsToRetry.isNotEmpty() && currentConfig?.isHardwareAccelerated == false) {
            Log.i(TAG, "使用软件编码重试 ${failedSegmentsToRetry.size} 个失败的片段")

            for ((originalIndex, segment) in failedSegmentsToRetry) {
                // 检查是否取消
                if (!isActive) break

                withContext(Dispatchers.Main) {
                    onProgress(Progress(
                        currentSegment = originalIndex + 1,
                        totalSegments = segmentList.size,
                        segmentProgress = 0,
                        overallProgress = 95, // 重试阶段显示接近完成
                        status = "重试片段 ${originalIndex + 1}（软件编码）"
                    ))
                }

                // 删除之前可能生成的损坏文件
                val segmentNumber = String.format("%02d", originalIndex + 1)
                val oldFile = File(config.outputDir, "${config.outputNamePrefix}_${segmentNumber}.mp4")
                if (oldFile.exists()) {
                    oldFile.delete()
                    Log.d(TAG, "删除失败片段的旧文件: ${oldFile.name}")
                }

                val retryResult = processSegment(
                    config = config,
                    segment = segment,
                    index = originalIndex,
                    totalSegments = segmentList.size,
                    onSegmentProgress = { segmentProgress ->
                        onProgress(Progress(
                            currentSegment = originalIndex + 1,
                            totalSegments = segmentList.size,
                            segmentProgress = segmentProgress,
                            overallProgress = 95,
                            status = "重试片段 ${originalIndex + 1} 编码中: $segmentProgress%"
                        ))
                    }
                )

                // 替换原来失败的结果
                val resultIndex = results.indexOfFirst { it.index == originalIndex }
                if (resultIndex >= 0) {
                    results[resultIndex] = retryResult
                }

                if (retryResult.success) {
                    Log.i(TAG, "片段 ${originalIndex + 1} 重试成功")
                } else {
                    Log.e(TAG, "片段 ${originalIndex + 1} 重试仍然失败: ${retryResult.error}")
                }
            }
        }

        results
    }
    
    /**
     * 并行分割
     */
    private suspend fun splitParallel(
        config: SplitConfig,
        segmentList: List<SegmentInfo>,
        onProgress: (Progress) -> Unit
    ): List<SegmentResult> = coroutineScope {
        
        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(parallelCount)
        
        Log.i(TAG, "并行处理: 最大并发数=$parallelCount")
        
        val deferredResults = segmentList.mapIndexed { index, segment ->
            async {
                semaphore.withPermit {
                    val result = processSegment(
                        config = config,
                        segment = segment,
                        index = index,
                        totalSegments = segmentList.size,
                        onSegmentProgress = { }
                    )
                    
                    // 更新总进度
                    val completed = completedCount.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        onProgress(Progress(
                            currentSegment = completed,
                            totalSegments = segmentList.size,
                            segmentProgress = 100,
                            overallProgress = (completed * 100) / segmentList.size,
                            status = "已完成 $completed/${segmentList.size}"
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
     * 硬件编码走 MediaCodec，软件编码走 FFmpeg
     */
    private suspend fun processSegment(
        config: SplitConfig,
        segment: SegmentInfo,
        index: Int,
        totalSegments: Int,
        onSegmentProgress: (Int) -> Unit
    ): SegmentResult {
        val segmentNumber = String.format("%02d", index + 1)
        val outputFile = File(config.outputDir, "${config.outputNamePrefix}_${segmentNumber}.mp4")

        val encoderConfig = currentConfig ?: EncoderConfig.DEFAULT_SOFTWARE

        // 硬件编码走纯 MediaCodec，软件编码走 FFmpeg
        return if (encoderConfig.isHardwareAccelerated) {
            processSegmentWithMediaCodec(config, segment, index, outputFile, onSegmentProgress)
        } else {
            processSegmentWithFFmpeg(config, segment, index, outputFile, encoderConfig, onSegmentProgress)
        }
    }

    /**
     * 使用纯 MediaCodec 处理片段（硬件加速）
     */
    private suspend fun processSegmentWithMediaCodec(
        config: SplitConfig,
        segment: SegmentInfo,
        index: Int,
        outputFile: File,
        onSegmentProgress: (Int) -> Unit
    ): SegmentResult {
        Log.d(TAG, "片段 ${index + 1}: 使用 MediaCodec 硬件编码")

        val startTimeUs = (segment.startTimeSec * 1_000_000).toLong()
        val endTimeUs = ((segment.startTimeSec + segment.durationSec) * 1_000_000).toLong()

        val splitSegment = MediaCodecSplitter.SplitSegment(
            startTimeUs = startTimeUs,
            endTimeUs = endTimeUs,
            outputFile = outputFile
        )

        val result = mediaCodecSplitter.splitSegment(
            inputPath = config.inputPath,
            segment = splitSegment,
            onProgress = onSegmentProgress
        )

        return if (result.success) {
            Log.i(TAG, "片段 ${index + 1} 完成: ${outputFile.name}")
            SegmentResult(
                index = index,
                success = true,
                outputFile = result.outputFile
            )
        } else {
            Log.e(TAG, "片段 ${index + 1} 失败: ${result.error}")
            SegmentResult(
                index = index,
                success = false,
                outputFile = null,
                error = result.error,
                ffmpegCommand = null,
                fullErrorLog = result.error
            )
        }
    }

    /**
     * 使用 FFmpeg 处理片段（软件编码）
     */
    private suspend fun processSegmentWithFFmpeg(
        config: SplitConfig,
        segment: SegmentInfo,
        index: Int,
        outputFile: File,
        encoderConfig: EncoderConfig,
        onSegmentProgress: (Int) -> Unit
    ): SegmentResult = suspendCancellableCoroutine { continuation ->

        // 构建 FFmpeg 命令（软件编码，-ss 放前面提高速度）
        val command = buildFFmpegCommand(
            inputPath = config.inputPath,
            startTimeSec = segment.startTimeSec,
            durationSec = segment.durationSec,
            outputPath = outputFile.absolutePath,
            encoderConfig = encoderConfig
        )

        val commandStr = command.joinToString(" ")
        Log.d(TAG, "片段 ${index + 1}: $commandStr")

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
                    val fullLog = session.allLogsAsString ?: ""
                    Log.e(TAG, "片段 ${index + 1} 失败: $fullLog")

                    val keyErrorLog = extractKeyErrorLog(fullLog)

                    continuation.resume(SegmentResult(
                        index = index,
                        success = false,
                        outputFile = null,
                        error = extractErrorSummary(fullLog),
                        ffmpegCommand = commandStr,
                        fullErrorLog = keyErrorLog
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

        continuation.invokeOnCancellation {
            Log.w(TAG, "片段 ${index + 1} 被取消")
            session.cancel()
        }
    }

    /**
     * 提取关键错误日志
     */
    private fun extractKeyErrorLog(fullLog: String): String {
        if (fullLog.isEmpty()) return ""

        // 提取包含 error/failed/configure 的行
        val errorLines = fullLog.lines()
            .filter {
                it.contains("error", ignoreCase = true) ||
                it.contains("failed", ignoreCase = true) ||
                it.contains("configure", ignoreCase = true) ||
                it.contains("codec", ignoreCase = true)
            }
            .takeLast(5)
            .joinToString("\n")

        if (errorLines.isNotEmpty()) {
            return errorLines.take(500)
        }

        // 如果没找到，返回最后 300 字符
        return fullLog.takeLast(300)
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
            addAll(listOf("-ss", startTimeSec.toString()))
            addAll(listOf("-i", inputPath))
            addAll(listOf("-t", durationSec.toString()))
            addAll(encoderConfig.buildOutputParams())
            addAll(listOf("-y", outputPath))
        }.toTypedArray()
    }
    
    /**
     * 计算分段信息
     */
    private fun calculateSegments(durationMs: Long, intervalSeconds: Int): List<SegmentInfo> {
        val durationSec = durationMs / 1000.0
        val segmentList = mutableListOf<SegmentInfo>()
        
        var currentTime = 0.0
        var index = 0
        
        while (currentTime < durationSec) {
            val remaining = durationSec - currentTime
            val segmentDuration = if (remaining < intervalSeconds) {
                if (remaining < 1.0 && segmentList.isNotEmpty()) {
                    // 不足1秒，合并到上一段
                    val lastSegment = segmentList.removeAt(segmentList.lastIndex)
                    segmentList.add(lastSegment.copy(
                        durationSec = lastSegment.durationSec + remaining
                    ))
                    break
                } else {
                    remaining
                }
            } else {
                intervalSeconds.toDouble()
            }
            
            segmentList.add(SegmentInfo(
                index = index,
                startTimeSec = currentTime,
                durationSec = segmentDuration
            ))
            
            currentTime += intervalSeconds
            index++
        }
        
        return segmentList
    }
    
    /**
     * 提取错误摘要
     */
    private fun extractErrorSummary(fullLog: String?): String {
        if (fullLog.isNullOrEmpty()) return "未知错误"

        // 硬件编码相关的详细错误
        val hardwareErrorPatterns = listOf(
            "configure failed" to "硬件编码器配置失败",
            "dequeueOutputBuffer" to "硬件编码器输出缓冲区错误",
            "dequeueInputBuffer" to "硬件编码器输入缓冲区错误",
            "queueInputBuffer" to "硬件编码器队列错误",
            "MediaCodec" to "MediaCodec 硬件编码错误",
            "h264_mediacodec" to "H.264 硬件编码器不兼容",
            "codec not currently supported" to "编码器不支持当前格式",
            "Failed to initialize" to "编码器初始化失败",
            "Error while opening encoder" to "无法打开编码器",
            "Encoder.*not found" to "找不到编码器"
        )

        // 通用错误
        val generalErrorPatterns = listOf(
            "No such file" to "文件不存在",
            "Permission denied" to "权限被拒绝",
            "Invalid data" to "无效数据",
            "Encoder not found" to "编码器未找到",
            "Out of memory" to "内存不足",
            "No space left" to "存储空间不足",
            "Invalid argument" to "参数无效",
            "Conversion failed" to "格式转换失败",
            "Error.*muxing" to "封装错误",
            "broken pipe" to "管道中断"
        )

        // 先检查硬件编码错误
        for ((pattern, message) in hardwareErrorPatterns) {
            if (fullLog.contains(pattern, ignoreCase = true)) {
                return message
            }
        }

        // 再检查通用错误
        for ((pattern, message) in generalErrorPatterns) {
            if (fullLog.contains(Regex(pattern, RegexOption.IGNORE_CASE))) {
                return message
            }
        }

        // 尝试提取 FFmpeg 的错误行
        val errorLine = fullLog.lines()
            .filter { it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true) }
            .lastOrNull { it.isNotBlank() }

        if (errorLine != null) {
            return errorLine.take(80)
        }

        return fullLog.trim().lines().lastOrNull { it.isNotBlank() }?.take(50) ?: "处理失败"
    }
    
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
