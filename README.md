# VideoSplitter 视频分割器

一个简单的 Android 应用，可以按固定时间间隔自动分割视频。

## 功能

- 选择本地视频文件
- 设置分割间隔（秒）
- 自动分割成多个视频片段
- 输出保存到 Movies/VideoSplitter 目录

## 使用方法

1. 打开应用
2. 点击「选择视频」按钮
3. 输入分割间隔（例如：3 秒）
4. 点击「开始分割」
5. 等待分割完成，视频保存在 Movies/VideoSplitter 文件夹

## 构建

### 使用 GitHub Actions（推荐）

1. Fork 此仓库
2. 进入 Actions 标签页
3. 运行 "Build APK" workflow
4. 下载生成的 APK

### 本地构建

```bash
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

## 技术栈

- Kotlin
- FFmpeg-kit
- Material Design 3
