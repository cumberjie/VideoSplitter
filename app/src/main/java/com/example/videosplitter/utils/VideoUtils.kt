package com.example.videosplitter.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 视频工具类
 */
object VideoUtils {
    
    private const val TAG = "VideoUtils"
    
    /**
     * 视频信息
     */
    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val rotation: Int,
        val bitrate: Long,
        val frameRate: Float
    ) {
        val durationSeconds: Double get() = durationMs / 1000.0
        
        val resolution: String get() = "${width}x${height}"
        
        val durationFormatted: String get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
        
        /**
         * 获取实际显示尺寸（考虑旋转）
         */
        val displaySize: Pair<Int, Int> get() {
            return if (rotation == 90 || rotation == 270) {
                Pair(height, width)
            } else {
                Pair(width, height)
            }
        }
    }
    
    /**
     * 获取视频信息
     */
    fun getVideoInfo(path: String): VideoInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            
            val bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toLongOrNull() ?: 0L
            
            // 帧率（Android 23+）
            val frameRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                )?.toFloatOrNull() ?: 30f
            } else {
                30f
            }
            
            VideoInfo(
                width = width,
                height = height,
                durationMs = duration,
                rotation = rotation,
                bitrate = bitrate,
                frameRate = frameRate
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取视频信息失败: ${e.message}", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "释放 MediaMetadataRetriever 失败", e)
            }
        }
    }
    
    /**
     * 尝试获取 Uri 的真实文件路径
     * 如果能获取到，就不需要复制文件
     */
    fun getRealPathFromUri(context: Context, uri: Uri): String? {
        // file:// 协议
        if (uri.scheme == "file") {
            return uri.path
        }
        
        // content:// 协议，尝试查询真实路径
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                        val path = cursor.getString(columnIndex)
                        if (!path.isNullOrEmpty() && File(path).exists()) {
                            Log.d(TAG, "获取到真实路径: $path")
                            return path
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法获取真实路径: ${e.message}")
            }
        }
        
        return null
    }
    
    /**
     * 复制 Uri 内容到缓存文件
     */
    fun copyUriToCache(context: Context, uri: Uri, fileName: String = "input_video.mp4"): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            
            Log.d(TAG, "已复制到缓存: ${cacheFile.absolutePath}, 大小: ${cacheFile.length() / 1024}KB")
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 智能获取视频路径
     * 优先使用真实路径，获取不到再复制到缓存
     */
    fun getVideoPath(context: Context, uri: Uri): String? {
        // 先尝试获取真实路径
        val realPath = getRealPathFromUri(context, uri)
        if (realPath != null) {
            Log.d(TAG, "使用真实路径: $realPath")
            return realPath
        }
        
        // 获取不到，复制到缓存
        Log.d(TAG, "无法获取真实路径，复制到缓存")
        return copyUriToCache(context, uri)?.absolutePath
    }
    
    /**
     * 清理缓存文件
     */
    fun cleanupCache(context: Context) {
        try {
            val cacheFile = File(context.cacheDir, "input_video.mp4")
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "已清理缓存文件")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理缓存失败: ${e.message}")
        }
    }
    
    /**
     * 格式化时长
     */
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
