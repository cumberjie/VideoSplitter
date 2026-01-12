package com.example.videosplitter.encoder

/**
 * 视频编码配置
 */
data class EncoderConfig(
    val videoCodec: String,                 // FFmpeg 视频编码器名称
    val videoCodecParams: List<String>,     // 视频编码参数
    val audioCodec: String = "aac",         // 音频编码器
    val audioBitrate: String = "192k",      // 音频比特率
    val isHardwareAccelerated: Boolean,     // 是否硬件加速
    val description: String,                // 描述信息
    val qualityLevel: QualityLevel = QualityLevel.HIGH  // 质量级别
) {
    
    /**
     * 质量级别
     */
    enum class QualityLevel(val displayName: String) {
        LOW("低质量 - 文件小"),
        MEDIUM("中等质量"),
        HIGH("高质量"),
        VERY_HIGH("超高质量 - 文件大")
    }
    
    /**
     * 构建完整的 FFmpeg 输出参数
     */
    fun buildOutputParams(): List<String> {
        return mutableListOf<String>().apply {
            // 视频编码
            addAll(listOf("-c:v", videoCodec))
            addAll(videoCodecParams)
            
            // 音频编码
            addAll(listOf("-c:a", audioCodec))
            addAll(listOf("-b:a", audioBitrate))
            
            // 通用参数
            addAll(listOf("-avoid_negative_ts", "make_zero"))
            
            // 像素格式（确保兼容性）
            addAll(listOf("-pix_fmt", "yuv420p"))
        }
    }
    
    companion object {
        /**
         * 默认软件编码配置
         */
        val DEFAULT_SOFTWARE = EncoderConfig(
            videoCodec = "libx264",
            videoCodecParams = listOf(
                "-crf", "18",
                "-preset", "fast"
            ),
            isHardwareAccelerated = false,
            description = "软件编码 (libx264)"
        )
    }
}
