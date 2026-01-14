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

    /**
     * 构建 FFmpeg 输出参数
     */
    fun buildOutputParams(): List<String> {
        val sanitizedVideoParams = if (isHardwareAccelerated && videoCodec == "h264_mediacodec") {
            removeUnsupportedMediaCodecParams(videoCodecParams)
        } else {
            videoCodecParams
        }
        return mutableListOf<String>().apply {
            addAll(listOf("-c:v", videoCodec))
            addAll(sanitizedVideoParams)
            addAll(listOf("-c:a", "aac", "-b:a", "128k"))
        }
    }

    private fun removeUnsupportedMediaCodecParams(params: List<String>): List<String> {
        val unsupportedFlags = setOf("-profile:v", "-profile", "-level", "-level:v")
        val sanitized = mutableListOf<String>()
        var skipNext = false
        for (param in params) {
            if (skipNext) {
                skipNext = false
                continue
            }
            if (unsupportedFlags.contains(param)) {
                skipNext = true
                continue
            }
            if (param.startsWith("-profile") || param.startsWith("-level")) {
                continue
            }
            sanitized.add(param)
        }
        return sanitized
    }

    companion object {
        /**
         * 默认软件编码配置
         */
        val DEFAULT_SOFTWARE = EncoderConfig(
            videoCodec = "libx264",
            videoCodecParams = listOf("-crf", "18", "-preset", "medium"),
            isHardwareAccelerated = false,
            description = "软件编码 (libx264)",
            qualityLevel = QualityLevel.HIGH
        )
    }
}
