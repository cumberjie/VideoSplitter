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
     * 获取硬件编码配置（纯 MediaCodec，不使用 FFmpeg）
     * 注意：此配置仅用于标识，实际编码由 MediaCodecSplitter 完成
     */
    fun getHardwareConfig(
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        qualityPreset: QualityPreset = QualityPreset.BALANCED
    ): EncoderConfig {

        return EncoderConfig(
            videoCodec = "mediacodec",  // 标识使用纯 MediaCodec
            videoCodecParams = emptyList(),  // MediaCodec 不需要 FFmpeg 参数
            isHardwareAccelerated = true,
            description = "🚀 硬件加速编码 (纯 MediaCodec)",
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
            QualityPreset.FAST -> Pair("20", "veryfast")      // CRF 20 质量更好
            QualityPreset.BALANCED -> Pair("16", "medium")    // CRF 16 高质量
            QualityPreset.QUALITY -> Pair("12", "slow")       // CRF 12 接近无损
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
