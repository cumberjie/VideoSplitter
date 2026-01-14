package com.example.videosplitter.encoder

/**
 * 编码器配置数据类
 */
data class EncoderConfig(
    val videoCodec: String,
    val videoCodecParams: List<String>,
    val isHardwareAccelerated: Boolean,
    val description: String,
    val qualityLevel: QualityLevel
) {
    /**
     * 质量等级枚举
     */
    enum class QualityLevel(val displayName: String) {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
        VERY_HIGH("极高")
    }
}
