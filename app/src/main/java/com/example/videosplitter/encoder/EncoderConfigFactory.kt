package com.example.videosplitter.encoder

import android.util.Log

/**
 * 编码器配置工厂
 * 根据设备能力和用户偏好生成最佳编码配置
 */
object EncoderConfigFactory {
    
    private const val TAG = "EncoderConfigFactory"
    
    /**
     * 视频质量预设
     */
    enum class QualityPreset {
        FAST,       // 快速，质量一般
        BALANCED,   // 平衡
        QUALITY     // 高质量，速度慢
    }
    
    /**
     * 获取最佳编码配置
     * 
     * @param preferHardware 是否优先使用硬件编码
     * @param videoWidth 视频宽度
     * @param videoHeight 视频高度
     * @param qualityPreset 质量预设
     */
    fun getBestConfig(
        preferHardware: Boolean = true,
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        qualityPreset: QualityPreset = QualityPreset.BALANCED
    ): EncoderConfig {
        
        Log.d(TAG, "获取编码配置: preferHardware=$preferHardware, " +
                "resolution=${videoWidth}x${videoHeight}, quality=$qualityPreset")
        
        // 如果不想用硬件编码，直接返回软件配置
        if (!preferHardware) {
            Log.d(TAG, "用户选择软件编码")
            return getSoftwareConfig(qualityPreset)
        }
        
        // 检测硬件能力
        val caps = HardwareCodecDetector.detectCapabilities()
        
        // 检查是否支持硬件编码
        if (!caps.supportsH264) {
            Log.w(TAG, "设备不支持 H.264 硬件编码，使用软件编码")
            return getSoftwareConfig(qualityPreset)
        }
        
        // 检查分辨率是否支持
        val maxRes = caps.maxResolution
        if (maxRes != null && (videoWidth > maxRes.first || videoHeight > maxRes.second)) {
            Log.w(TAG, "视频分辨率 ${videoWidth}x${videoHeight} 超出硬件能力 " +
                    "${maxRes.first}x${maxRes.second}，使用软件编码")
            return getSoftwareConfig(qualityPreset)
        }
        
        // 返回硬件编码配置
        Log.i(TAG, "使用硬件编码: ${caps.h264EncoderName}")
        return getHardwareConfig(videoWidth, videoHeight, qualityPreset)
    }
    
    /**
     * 获取硬件编码配置
     */
    fun getHardwareConfig(
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        qualityPreset: QualityPreset = QualityPreset.BALANCED
    ): EncoderConfig {
        
        // 根据分辨率计算推荐比特率
        val bitrate = calculateRecommendedBitrate(videoWidth, videoHeight, qualityPreset)
        val maxBitrate = (bitrate * 1.25).toLong()
        val bufferSize = bitrate * 2
        
        return EncoderConfig(
            videoCodec = "h264_mediacodec",
            videoCodecParams = listOf(
                "-b:v", "${bitrate / 1_000_000}M",       // 目标比特率
                "-maxrate", "${maxBitrate / 1_000_000}M", // 最大比特率
                "-bufsize", "${bufferSize / 1_000_000}M", // 缓冲区大小
                "-profile:v", "high",                     // H.264 High Profile
                "-level", "4.1"                           // Level 4.1 (支持 1080p60)
            ),
            isHardwareAccelerated = true,
            description = "🚀 硬件加速编码 (MediaCodec)",
            qualityLevel = when (qualityPreset) {
                QualityPreset.FAST -> EncoderConfig.QualityLevel.MEDIUM
                QualityPreset.BALANCED -> EncoderConfig.QualityLevel.HIGH
                QualityPreset.QUALITY -> EncoderConfig.QualityLevel.VERY_HIGH
            }
        )
    }
    
    /**
     * 获取软件编码配置
     */
    fun getSoftwareConfig(
        qualityPreset: QualityPreset = QualityPreset.BALANCED
    ): EncoderConfig {
        
        val (crf, preset) = when (qualityPreset) {
            QualityPreset.FAST -> Pair("23", "veryfast")
            QualityPreset.BALANCED -> Pair("18", "fast")
            QualityPreset.QUALITY -> Pair("15", "slow")
        }
        
        return EncoderConfig(
            videoCodec = "libx264",
            videoCodecParams = listOf(
                "-crf", crf,
                "-preset", preset
            ),
            isHardwareAccelerated = false,
            description = "💻 软件编码 (libx264)",
            qualityLevel = when (qualityPreset) {
                QualityPreset.FAST -> EncoderConfig.QualityLevel.MEDIUM
                QualityPreset.BALANCED -> EncoderConfig.QualityLevel.HIGH
                QualityPreset.QUALITY -> EncoderConfig.QualityLevel.VERY_HIGH
            }
        )
    }
    
    /**
     * 根据分辨率和质量预设计算推荐比特率
     */
    private fun calculateRecommendedBitrate(
        width: Int,
        height: Int,
        qualityPreset: QualityPreset
    ): Long {
        // 基础比特率（每像素）
        val bitsPerPixel = when (qualityPreset) {
            QualityPreset.FAST -> 0.1
            QualityPreset.BALANCED -> 0.15
            QualityPreset.QUALITY -> 0.2
        }
        
        val pixels = width * height
        val baseBitrate = (pixels * bitsPerPixel * 30).toLong() // 假设 30fps
        
        // 限制范围
        return baseBitrate.coerceIn(2_000_000L, 50_000_000L)
    }
    
    /**
     * 获取编码器描述信息（用于 UI 显示）
     */
    fun getEncoderDescription(config: EncoderConfig): String {
        return buildString {
            appendLine(config.description)
            appendLine("质量: ${config.qualityLevel.displayName}")
            if (config.isHardwareAccelerated) {
                appendLine("⚡ 速度快，CPU 占用低")
            } else {
                appendLine("🔧 兼容性好，质量稳定")
            }
        }
    }
}
