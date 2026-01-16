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
            encoder = createHardwareEncoder(mime)
            if (encoder == null) {
                return@withContext SegmentResult(false, null, "无法创建硬件编码器")
            }

            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // 6. 创建解码器，输出到编码器的 Surface
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
            var outputDone = false
            var decoderOutputAvailable = true

            // 11. 解码-编码循环
            while (!outputDone && isActive) {
                // 喂数据给解码器
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0 || extractor.sampleTime > segment.endTimeUs) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime - segment.startTimeUs
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                                presentationTimeUs.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }

                // 从解码器获取输出
                if (decoderOutputAvailable) {
                    val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            decoderOutputAvailable = false
                        }
                        decoderStatus >= 0 -> {
                            val doRender = bufferInfo.size != 0
                            decoder.releaseOutputBuffer(decoderStatus, doRender)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoder.signalEndOfInputStream()
                                decoderOutputAvailable = false
                            }
                        }
                    }
                }

                // 从编码器获取输出
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            muxerVideoTrack = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)

                            // 更新进度
                            val progress = ((bufferInfo.presentationTimeUs * 100) / totalDurationUs)
                                .toInt().coerceIn(0, 100)
                            onProgress(progress)
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }

                // 重置标志
                if (!decoderOutputAvailable && !inputDone) {
                    decoderOutputAvailable = true
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
            try {
                decoder?.stop()
                decoder?.release()
                encoder?.stop()
                encoder?.release()
                muxer?.stop()
                muxer?.release()
                extractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "释放资源时出错: ${e.message}")
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
    private fun createHardwareEncoder(mime: String): MediaCodec? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue

            // 优先选择硬件编码器
            val name = info.name.lowercase()
            if (name.contains("omx.google") || name.contains("c2.android") || name.contains("sw")) {
                continue
            }

            try {
                Log.d(TAG, "尝试使用编码器: ${info.name}")
                return MediaCodec.createByCodecName(info.name)
            } catch (e: Exception) {
                Log.w(TAG, "创建编码器失败: ${info.name}, ${e.message}")
            }
        }

        return null
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
