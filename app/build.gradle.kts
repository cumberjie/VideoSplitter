// app/build.gradle.kts

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ========== 自动下载 AAR 文件的任务 ==========
val downloadFfmpegAar by tasks.registering {
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

tasks.named("preBuild") {
    dependsOn(downloadFfmpegAar)
}

android {
    namespace = "com.example.videosplitter"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (!keystoreFile.isNullOrEmpty()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.videosplitter"
        minSdk = 24
        targetSdk = 34
        versionCode = 4  // 版本号 +1
        versionName = "1.4"  // 添加硬件加速版本
        
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    
    implementation(files("libs/ffmpeg-kit-min-gpl-7.1.5.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")
    
    // ========== 新增：协程支持 ==========
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
