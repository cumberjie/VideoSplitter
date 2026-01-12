package com.example.videosplitter

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * 硬件编码器检测和配置（简化版，单文件）
 */
object HardwareEncoder {
    
    private const val TAG = "HardwareEncoder"
    
    // 缓存检测结果
    private var cachedSupport: Boolean? = null
    private var cachedEncoderName: String? = null
    
    /**
     * 检测是否支持硬件编码
     */
    fun isHardwareEncodingSupported(): Boolean {
        cachedSupport?.let { return it }
        
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!isHardwareCodec(codecInfo)) continue
            
            for (type in codecInfo.supportedTypes) {
                if (type.equals("video/avc", ignoreCase = true)) {
                    cachedSupport = true
                    cachedEncoderName = codecInfo.name
                    Log.i(TAG, "找到硬件编码器: ${codecInfo.name}")
                    return true
                }
            }
        }
        
        cachedSupport = false
        Log.w(TAG, "未找到硬件编码器")
        return false
    }
    
    /**
     * 获取硬件编码器名称
     */
    fun getEncoderName(): String? {
        if (cachedEncoderName == null) {
            isHardwareEncodingSupported()
        }
        return cachedEncoderName
    }
    
    /**
     * 判断是否是硬件编码器
     */
    private fun isHardwareCodec(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codecInfo.isHardwareAccelerated
        }
        
        val name = codecInfo.name.lowercase()
        val softwarePrefixes = listOf("omx.google.", "c2.android.", "c2.google.", "avcodec", "ffmpeg")
        return softwarePrefixes.none { name.contains(it) }
    }
    
    /**
     * 获取 FFmpeg 视频编码参数
     */
    fun getVideoEncoderParams(useHardware: Boolean): List<String> {
        return if (useHardware && isHardwareEncodingSupported()) {
            listOf(
                "-c:v", "h264_mediacodec",
                "-b:v", "8M",
                "-maxrate", "10M",
                "-bufsize", "16M"
            )
        } else {
            listOf(
                "-c:v", "libx264",
                "-crf", "18",
                "-preset", "fast"
            )
        }
    }
    
    /**
     * 获取编码器描述信息
     */
    fun getEncoderDescription(useHardware: Boolean): String {
        return if (useHardware && isHardwareEncodingSupported()) {
            "🚀 硬件加速 (${cachedEncoderName ?: "MediaCodec"})\n⚡ 预计速度提升 3-5 倍"
        } else if (!isHardwareEncodingSupported()) {
            "⚠️ 设备不支持硬件加速\n💻 使用软件编码"
        } else {
            "💻 软件编码 (libx264)\n🔧 兼容性好，质量稳定"
        }
    }
}
