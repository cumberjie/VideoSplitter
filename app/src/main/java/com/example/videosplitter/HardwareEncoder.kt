    // ========== 修改：使用协程的分割方法 ==========
    private fun startSplitting() {
        val intervalText = etInterval.text.toString()
        if (intervalText.isEmpty()) {
            tvStatus.text = "请输入分割间隔秒数"
            return
        }
        val interval = intervalText.toIntOrNull()
        if (interval == null || interval <= 0) {
            tvStatus.text = "请输入有效的秒数"
            return
        }
        if (selectedVideoPath == null) {
            tvStatus.text = "请先选择视频"
            return
        }
        
        val durationSec = videoDurationMs / 1000.0
        if (interval > durationSec) {
            tvStatus.text = "⚠️ 分割间隔超过视频时长"
            return
        }

        val outputDir = getOutputDirectory()
        val displayPath = getOutputDisplayPath()
        val (totalSegments, segmentDurations) = calculateSegments(durationSec, interval)
        val useHardware = switchHardwareEncoder.isChecked
        
        // 设置 UI 状态
        setProcessingState(true)
        
        val encoderInfo = if (useHardware) "🚀 硬件加速" else "💻 软件编码"
        tvStatus.text = "预计生成 $totalSegments 个片段\n$encoderInfo"
        
        Log.i(TAG, "开始分割: 总时长=${durationSec}秒, 间隔=${interval}秒, 硬件加速=$useHardware")

        // 使用协程执行分割
        splitJob = lifecycleScope.launch {
            var successCount = 0
            var failedCount = 0
            val startTime = System.currentTimeMillis()

            try {
                for (i in 0 until totalSegments) {
                    // 检查是否取消
                    ensureActive()
                    
                    val startTimeSec = i * interval
                    val currentSegment = i + 1
                    val segmentDuration = segmentDurations[i]
                  
                    val segmentNumber = String.format("%02d", currentSegment)
                    val outputFile = File(outputDir, "${originalFileName}_${segmentNumber}.mp4")

                    // 更新进度
                    tvProgressDetail.text = "片段 $currentSegment/$totalSegments 编码中..."
                    
                    // 在 IO 线程执行 FFmpeg
                    val success = withContext(Dispatchers.IO) {
                        processSegment(
                            startTimeSec = startTimeSec,
                            duration = segmentDuration,
                            outputFile = outputFile,
                            useHardware = useHardware,
                            onProgress = { segmentProgress ->
                                val overallProgress = ((i + segmentProgress / 100f) / totalSegments * 100).toInt()
                                launch(Dispatchers.Main) {
                                    progressBar.progress = overallProgress
                                    tvProgressPercent.text = "正在分割 $overallProgress%"
                                    tvProgressDetail.text = "片段 $currentSegment/$totalSegments: $segmentProgress%"
                                }
                            }
                        )
                    }

                    if (success) {
                        successCount++
                        // 刷新媒体库
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(outputFile.absolutePath),
                            arrayOf("video/mp4"),
                            null
                        )
                    } else {
                        failedCount++
                    }

                    // 更新总进度
                    val overallProgress = ((currentSegment.toFloat() / totalSegments) * 100).toInt()
                    progressBar.progress = overallProgress
                    tvProgressPercent.text = "正在分割 $overallProgress%"
                }
                
                // 完成
                val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
                showCompletionResult(successCount, failedCount, totalTime, displayPath, useHardware)
                
            } catch (e: CancellationException) {
                tvStatus.text = "❌ 已取消分割\n成功: $successCount 个"
            } catch (e: Exception) {
                tvStatus.text = "❌ 分割失败: ${e.message}"
                Log.e(TAG, "分割失败", e)
            } finally {
                setProcessingState(false)
                cleanupCacheFiles()
            }
        }
    }
    
    /**
     * 处理单个片段（在 IO 线程执行）
     */
    private suspend fun processSegment(
        startTimeSec: Int,
        duration: Double,
        outputFile: File,
        useHardware: Boolean,
        onProgress: (Int) -> Unit
    ): Boolean = suspendCancellableCoroutine { continuation ->
        
        // 构建 FFmpeg 命令
        val command = mutableListOf<String>().apply {
            addAll(listOf("-ss", startTimeSec.toString()))
            addAll(listOf("-i", selectedVideoPath!!))
            addAll(listOf("-t", duration.toString()))
            
            // 视频编码参数（硬件或软件）
            addAll(HardwareEncoder.getVideoEncoderParams(useHardware))
            
            // 音频编码参数
            addAll(listOf("-c:a", "aac", "-b:a", "192k"))
            
            // 其他参数
            addAll(listOf("-avoid_negative_ts", "make_zero"))
            addAll(listOf("-pix_fmt", "yuv420p"))
            addAll(listOf("-y", outputFile.absolutePath))
        }.toTypedArray()
        
        Log.d(TAG, "FFmpeg 命令: ${command.joinToString(" ")}")
        
        val targetDurationMs = (duration * 1000).toLong()
        
        val session = FFmpegKit.executeWithArgumentsAsync(
            command,
            { session ->
                val success = ReturnCode.isSuccess(session.returnCode)
                if (!success) {
                    Log.e(TAG, "FFmpeg 失败: ${session.allLogsAsString}")
                }
                continuation.resume(success) {}
            },
            { log ->
                Log.v(TAG, log.message)
            },
            { statistics ->
                val timeMs = statistics.time
                if (timeMs > 0 && targetDurationMs > 0) {
                    val progress = min((timeMs.toFloat() / targetDurationMs * 100).toInt(), 100)
                    onProgress(progress)
                }
            }
        )
        
        // 支持取消
        continuation.invokeOnCancellation {
            session.cancel()
        }
    }
    
    /**
     * 设置处理状态
     */
    private fun setProcessingState(isProcessing: Boolean) {
        btnSplit.isEnabled = !isProcessing
        btnSelectVideo.isEnabled = !isProcessing
        switchHardwareEncoder.isEnabled = !isProcessing
        
        btnCancel.visibility = if (isProcessing) View.VISIBLE else View.GONE
        progressContainer.visibility = if (isProcessing) View.VISIBLE else View.GONE
        spinnerProgress.visibility = if (isProcessing) View.VISIBLE else View.GONE
        
        if (isProcessing) {
            progressBar.progress = 0
            tvProgressPercent.text = "准备中..."
            tvProgressDetail.text = ""
        }
    }
    
    /**
     * 显示完成结果
     */
    private fun showCompletionResult(
        successCount: Int,
        failedCount: Int,
        totalTimeSeconds: Double,
        displayPath: String,
        usedHardware: Boolean
    ) {
        val encoderInfo = if (usedHardware) "🚀 硬件加速" else "💻 软件编码"
        
        if (failedCount == 0) {
            tvStatus.text = buildString {
                appendLine("✅ 分割完成！")
                appendLine("生成了 $successCount 个视频片段")
                appendLine("耗时: ${String.format("%.1f", totalTimeSeconds)} 秒 | $encoderInfo")
                appendLine("保存位置: $displayPath")
            }
        } else {
            tvStatus.text = buildString {
                appendLine("⚠️ 分割部分完成")
                appendLine("成功: $successCount 个 | 失败: $failedCount 个")
                appendLine("耗时: ${String.format("%.1f", totalTimeSeconds)} 秒")
                appendLine("保存位置: $displayPath")
            }
        }
        
        progressBar.progress = 100
        tvProgressPercent.text = "分割完成 100%"
        spinnerProgress.visibility = View.GONE
        
        // 2秒后隐藏进度条
        progressContainer.postDelayed({
            if (!isFinishing && !isDestroyed) {
                progressContainer.visibility = View.GONE
            }
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        splitJob?.cancel()
        cleanupCacheFiles()
    }
}
