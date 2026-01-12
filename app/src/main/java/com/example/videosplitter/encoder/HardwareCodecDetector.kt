package com.example.videosplitter.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * 硬件编码能力检测器
 * 用于检测设备支持的硬件视频编码器
 */
object HardwareCodecDetector {
    
    private const val TAG = "HardwareCodecDetector"
    
    /**
     * 设备编码能力信息
     */
    data class CodecCapability(
        val supportsH264: Boolean,          // 是否支持 H.264 硬件编码
        val supportsHEVC: Boolean,          // 是否支持 H.265 硬件编码
        val h264EncoderName: String?,       // H.264 编码器名称
        val hevcEncoderName: String?,       // H.265 编码器名称
        val maxWidth: Int,                  // 最大支持宽度
        val maxHeight: Int,                 // 最大支持高度
        val supportedBitrateModes: List<String> // 支持的码率控制模式
    ) {
        val maxResolution: Pair<Int, Int>?
            get() = if (maxWidth > 0 && maxHeight > 0) Pair(maxWidth, maxHeight) else null
        
        override fun toString(): String {
            return buildString {
                appendLine("=== 设备编码能力 ===")
                appendLine("H.264 硬件编码: ${if (supportsH264) "✅ $h264EncoderName" else "❌ 不支持"}")
                appendLine("H.265 硬件编码: ${if (supportsHEVC) "✅ $hevcEncoderName" else "❌ 不支持"}")
                if (maxWidth > 0) {
                    appendLine("最大分辨率: ${maxWidth}x${maxHeight}")
                }
            }
        }
    }
    
    // 缓存检测结果，避免重复检测
    private var cachedCapability: CodecCapability? = null
    
    /**
     * 检测设备的硬件编码能力
     * @param forceRefresh 是否强制重新检测
     */
    fun detectCapabilities(forceRefresh: Boolean = false): CodecCapability {
        // 使用缓存
        if (!forceRefresh && cachedCapability != null) {
            return cachedCapability!!
        }
        
        Log.d(TAG, "开始检测硬件编码能力...")
        
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfos = codecList.codecInfos
        
        var h264Encoder: String? = null
        var hevcEncoder: String? = null
        var maxWidth = 0
        var maxHeight = 0
        val bitrateModes = mutableListOf<String>()
        
        for (codecInfo in codecInfos) {
            // 只检查编码器（不是解码器）
            if (!codecInfo.isEncoder) continue
            
            // 只检查硬件编码器
            if (!isHardwareCodec(codecInfo)) continue
            
            for (type in codecInfo.supportedTypes) {
                when {
                    // H.264 / AVC
                    type.equals("video/avc", ignoreCase = true) -> {
                        if (h264Encoder == null) {
                            h264Encoder = codecInfo.name
                            Log.d(TAG, "找到 H.264 硬件编码器: ${codecInfo.name}")
                            
                            try {
                                val caps = codecInfo.getCapabilitiesForType(type)
                                val videoCaps = caps.videoCapabilities
                                maxWidth = maxOf(maxWidth, videoCaps.supportedWidths.upper)
                                maxHeight = maxOf(maxHeight, videoCaps.supportedHeights.upper)
                                
                                // 检查支持的码率模式
                                val encoderCaps = caps.encoderCapabilities
                                if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                                    bitrateModes.add("VBR")
                                }
                                if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                                    bitrateModes.add("CBR")
                                }
                                if (encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
                                    bitrateModes.add("CQ")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "获取编码器能力失败: ${e.message}")
                            }
                        }
                    }
                    // H.265 / HEVC
                    type.equals("video/hevc", ignoreCase = true) -> {
                        if (hevcEncoder == null) {
                            hevcEncoder = codecInfo.name
                            Log.d(TAG, "找到 H.265 硬件编码器: ${codecInfo.name}")
                        }
                    }
                }
            }
        }
        
        val capability = CodecCapability(
            supportsH264 = h264Encoder != null,
            supportsHEVC = hevcEncoder != null,
            h264EncoderName = h264Encoder,
            hevcEncoderName = hevcEncoder,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            supportedBitrateModes = bitrateModes
        )
        
        Log.i(TAG, capability.toString())
        cachedCapability = capability
        
        return capability
    }
    
    /**
     * 判断是否是硬件编码器
     */
    private fun isHardwareCodec(codecInfo: MediaCodecInfo): Boolean {
        // Android 10+ 可以直接判断
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codecInfo.isHardwareAccelerated
        }
        
        // 旧版本通过名称判断
        val name = codecInfo.name.lowercase()
        
        // 软件编码器的常见名称前缀
        val softwareCodecPrefixes = listOf(
            "omx.google.",      // Google 软件编码器
            "c2.android.",      // Android Codec 2.0 软件编码器
            "c2.google.",       // Google Codec 2.0 软件编码器
            "avcodec",          // FFmpeg 软件编码器
            "ffmpeg",
            "sw.",              // 软件编码器通用前缀
            "software"
        )
        
        for (prefix in softwareCodecPrefixes) {
            if (name.contains(prefix)) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * 检查指定分辨率是否支持硬件编码
     */
    fun isResolutionSupported(width: Int, height: Int): Boolean {
        val caps = detectCapabilities()
        if (!caps.supportsH264) return false
        
        val maxRes = caps.maxResolution ?: return true
        return width <= maxRes.first && height <= maxRes.second
    }
    
    /**
     * 快速检查是否支持硬件编码
     */
    fun isHardwareEncodingAvailable(): Boolean {
        return detectCapabilities().supportsH264
    }
}
