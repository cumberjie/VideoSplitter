// app/build.gradle.kts

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ========== 自动下载 AAR 文件的任务 ==========
val downloadFfmpegAar by tasks.registering {
    // ========== 改成 min-gpl 文件名 ==========
    val aarFile = file("libs/ffmpeg-kit-min-gpl-7.1.5.aar")
    outputs.file(aarFile)
    
    doLast {
        if (!aarFile.exists()) {
            println("正在下载 ffmpeg-kit-min-gpl-7.1.5.aar ...")
            aarFile.parentFile.mkdirs()
            
      
            val url = "https://github.com/cumberjie/ffmpeg-kit-full-gpl/releases/download/v2/ffmpeg-kit-min-gpl-7.1.5.aar"
            
            uri(url).toURL().openStream().use { input ->
                aarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("下载完成: ${aarFile.absolutePath}")
        } else {
            println("AAR 文件已存在，跳过下载")
        }
    }
}

// 确保在编译前先下载 AAR
tasks.named("preBuild") {
    dependsOn(downloadFfmpegAar)
}

android {
    namespace = "com.example.videosplitter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videosplitter"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        
        // ========== 新增：只保留 arm64-v8a 架构 ==========
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // ========== 改成 min-gpl AAR 文件 ==========
    implementation(files("libs/ffmpeg-kit-min-gpl-7.1.5.aar"))
    
    // ffmpeg-kit 的传递依赖（AAR 不会自动引入，需要手动添加）
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
