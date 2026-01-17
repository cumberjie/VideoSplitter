package com.example.videosplitter.encoder

import android.media.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 纯 MediaCodec 硬件视频分割器
 * 不走 FFmpeg，直接使用 Android 原生 API
 */
class MediaCodecSplitter {

    private val TAG = "MediaCodecSplitter"

    data class SplitSegment(
        val startTimeUs: Long,
        val endTimeUs: Long,
        val outputFile: File
    )

    data class SegmentResult(
        val success: Boolean,
        val outputFile: File?,
        val error: String? = null
    )

    /**
     * 分割单个片段
     */
    suspend fun splitSegment(
        inputPath: String,
        segment: SplitSegment,
        onProgress: (Int) -> Unit
    ): SegmentResult = withContext(Dispatchers.IO) {

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: android.view.Surface? = null

        try {
            // 1. 初始化 MediaExtractor
            extractor = MediaExtractor().apply {
                setDataSource(inputPath)
            }

            // 2. 找到视频轨道
            val videoTrackIndex = findVideoTrack(extractor)
            if (videoTrackIndex < 0) {
                return@withContext SegmentResult(false, null, "找不到视频轨道")
            }

            extractor.selectTrack(videoTrackIndex)
            val inputFormat = extractor.getTrackFormat(videoTrackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = inputFormat.getIntegerSafe(MediaFormat.KEY_FRAME_RATE, 30)
            val bitRate = inputFormat.getIntegerSafe(MediaFormat.KEY_BIT_RATE, width * height * 3)

            Log.d(TAG, "输入视频: ${width}x${height}, $frameRate fps, $bitRate bps")

            // 3. 对齐分辨率到16的倍数
            val alignedWidth = (width / 16) * 16
            val alignedHeight = (height / 16) * 16

            // 4. 配置编码器输出格式
            val outputFormat = MediaFormat.createVideoFormat(mime, alignedWidth, alignedHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate.coerceAtMost(8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            // 5. 创建硬件编码器
            encoder = createHardwareEncoder(mime, outputFormat)
            if (encoder == null) {
                return@withContext SegmentResult(false, null, "无法创建硬件编码器")
            }

            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            // 6. 创建解码器，输出到编码器的 Surface（Zero-Copy）
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, inputSurface, null, 0)
            decoder.start()

            // 7. 创建 Muxer
            muxer = MediaMuxer(segment.outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 8. Seek 到起始位置
            extractor.seekTo(segment.startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // 9. 处理音频轨道（直接复制）
            val audioTrackIndex = findAudioTrack(extractor)
            var muxerAudioTrack = -1
            if (audioTrackIndex >= 0) {
                val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                muxerAudioTrack = muxer.addTrack(audioFormat)
            }

            // 10. 添加视频轨道到 Muxer
            var muxerVideoTrack = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val totalDurationUs = segment.endTimeUs - segment.startTimeUs
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            // 11. 解码-编码循环（针对低端设备优化）
            val timeoutUs = 10000L  // 10ms 超时
            val loopStartTime = System.currentTimeMillis()

            // 基于时间的超时：每个片段最多处理 10 分钟（针对长片段和低端设备）
            val maxLoopTimeMs = 600000L  // 10 分钟

            // 基于空转次数的超时：连续空转 30000 次才报错（约 300 秒）
            var consecutiveEmptyCount = 0
            val maxConsecutiveEmpty = 30000

            var lastProgressTime = System.currentTimeMillis()

            while (!encoderDone && isActive) {
                var didWork = false

                // 检查总超时（基于时间）
                val elapsedMs = System.currentTimeMillis() - loopStartTime
                if (elapsedMs > maxLoopTimeMs) {
                    Log.e(TAG, "处理超时（${elapsedMs}ms），强制退出")
                    throw IllegalStateException("视频处理超时，可能是硬件编码器卡死")
                }

                // 喂数据给解码器
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                    when {
                        inputBufferIndex >= 0 -> {
                            didWork = true
                            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)

                            if (sampleSize < 0 || extractor.sampleTime > segment.endTimeUs) {
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                Log.d(TAG, "输入完成")
                            } else {
                                val presentationTimeUs = extractor.sampleTime - segment.startTimeUs
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                                    presentationTimeUs.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                        inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // 硬件正在忙，这是正常现象，不计入超时
                        }
                        else -> {
                            // 其他错误码，记录但不中断
                            Log.w(TAG, "解码器 dequeueInputBuffer 返回: $inputBufferIndex")
                        }
                    }
                }

                // 从解码器获取输出（即使 inputDone 也要继续处理）
                if (!decoderDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        decoderStatus >= 0 -> {
                            didWork = true
                            val doRender = bufferInfo.size != 0
                            decoder.releaseOutputBuffer(decoderStatus, doRender)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoder.signalEndOfInputStream()
                                decoderDone = true
                                Log.d(TAG, "解码完成")
                            }
                        }
                        decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            didWork = true
                            Log.d(TAG, "解码器输出格式变化")
                        }
                        decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // 硬件正在忙，这是正常现象，不计入超时
                        }
                        else -> {
                            // 其他状态码，记录但不中断
                            Log.w(TAG, "解码器 dequeueOutputBuffer 返回: $decoderStatus")
                        }
                    }
                }

                // 从编码器获取输出
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        didWork = true
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            muxerVideoTrack = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer 启动")
                        }
                    }
                    encoderStatus >= 0 -> {
                        didWork = true
                        val encodedData = encoder.getOutputBuffer(encoderStatus)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)

                            // 更新进度
                            val progress = ((bufferInfo.presentationTimeUs * 100) / totalDurationUs)
                                .toInt().coerceIn(0, 100)
                            onProgress(progress)

                            lastProgressTime = System.currentTimeMillis()
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            Log.d(TAG, "编码完成")
                        }
                    }
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 硬件正在忙，这是正常现象，不计入超时
                    }
                    else -> {
                        // 其他状态码，记录但不中断
                        Log.w(TAG, "编码器 dequeueOutputBuffer 返回: $encoderStatus")
                    }
                }

                // 防止死循环（只有真正空转才计数）
                if (didWork) {
                    consecutiveEmptyCount = 0
                } else {
                    consecutiveEmptyCount++

                    // 每 5000 次空转记录一次日志，帮助调试
                    if (consecutiveEmptyCount % 5000 == 0) {
                        val timeSinceProgress = System.currentTimeMillis() - lastProgressTime
                        Log.d(TAG, "空转计数: $consecutiveEmptyCount, 距上次进度: ${timeSinceProgress}ms")
                    }

                    if (consecutiveEmptyCount > maxConsecutiveEmpty) {
                        Log.e(TAG, "连续空转超过阈值（${consecutiveEmptyCount}次），强制退出")
                        throw IllegalStateException("硬件编码器长时间无响应，可能不兼容")
                    }
                }
            }

            // 12. 复制音频数据
            if (audioTrackIndex >= 0 && muxerAudioTrack >= 0 && muxerStarted) {
                copyAudioTrack(inputPath, segment, muxer, muxerAudioTrack)
            }

            Log.i(TAG, "片段分割完成: ${segment.outputFile.name}")
            SegmentResult(true, segment.outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "分割失败: ${e.message}", e)
            SegmentResult(false, null, e.message ?: "未知错误")
        } finally {
            // 严格按顺序释放资源
            try {
                // 1. 停止并释放解码器
                decoder?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "停止解码器失败: ${e.message}")
                    }
                    try {
                        it.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放解码器失败: ${e.message}")
                    }
                }

                // 2. 停止并释放编码器
                encoder?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "停止编码器失败: ${e.message}")
                    }
                    try {
                        it.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放编码器失败: ${e.message}")
                    }
                }

                // 3. 显式释放 Surface
                inputSurface?.let {
                    try {
                        it.release()
                        Log.d(TAG, "Surface 已释放")
                    } catch (e: Exception) {
                        Log.w(TAG, "释放 Surface 失败: ${e.message}")
                    }
                }

                // 4. 停止并释放 Muxer
                muxer?.let {
                    try {
                        it.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "停止 Muxer 失败: ${e.message}")
                    }
                    try {
                        it.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放 Muxer 失败: ${e.message}")
                    }
                }

                // 5. 释放 Extractor
                extractor?.let {
                    try {
                        it.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放 Extractor 失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "释放资源时出错: ${e.message}")
            }
        }
    }

    /**
     * 复制音频轨道
     */
    private fun copyAudioTrack(
        inputPath: String,
        segment: SplitSegment,
        muxer: MediaMuxer,
        muxerAudioTrack: Int
    ) {
        val extractor = MediaExtractor().apply {
            setDataSource(inputPath)
        }

        try {
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) return

            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(segment.startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0 || extractor.sampleTime > segment.endTimeUs) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (extractor.sampleTime - segment.startTimeUs).coerceAtLeast(0)
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * 查找视频轨道
     */
    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        return -1
    }

    /**
     * 查找音频轨道
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }

    /**
     * 创建硬件编码器
     */
    private fun createHardwareEncoder(mime: String, format: MediaFormat): MediaCodec? {
        // 方法1：让系统自动选择最佳编码器
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val encoderName = codecList.findEncoderForFormat(format)
            if (encoderName != null) {
                Log.d(TAG, "系统推荐编码器: $encoderName")
                // 检查是否是软件编码器
                val nameLower = encoderName.lowercase()
                if (!nameLower.contains("omx.google") && !nameLower.contains("c2.android.avc") && !nameLower.contains("sw")) {
                    return MediaCodec.createByCodecName(encoderName)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findEncoderForFormat 失败: ${e.message}")
        }

        // 方法2：手动遍历查找硬件编码器
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue

            val name = info.name.lowercase()

            // 跳过明确的软件编码器
            if (name.contains("omx.google") ||
                name.contains("c2.android.avc") ||
                name.contains("swcodec") ||
                name == "c2.android.h264.encoder") {
                Log.d(TAG, "跳过软件编码器: ${info.name}")
                continue
            }

            try {
                Log.d(TAG, "尝试使用编码器: ${info.name}")
                return MediaCodec.createByCodecName(info.name)
            } catch (e: Exception) {
                Log.w(TAG, "创建编码器失败: ${info.name}, ${e.message}")
            }
        }

        // 方法3：最后尝试直接按 MIME 创建（让系统决定）
        return try {
            Log.d(TAG, "尝试默认编码器")
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            Log.e(TAG, "无法创建任何编码器: ${e.message}")
            null
        }
    }

    /**
     * 安全获取 Integer，带默认值
     */
    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else default
        } catch (e: Exception) {
            default
        }
    }
}
