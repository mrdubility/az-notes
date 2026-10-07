plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.az.notes"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // debug 独立包名（.debug 后缀）+ 仓库内置调试密钥签名：与正式版（com.az.notes）
    // 共存安装、互不覆盖；CI 每次在全新 runner 上自动生成的调试密钥不同，会导致
    // 前后构建的 APK 无法覆盖安装，改用仓库内置密钥后签名保持一致。
    // （keystore/debug.keystore 为公知密码 android 的调试密钥，发布密钥仍不入库）
    //
    // 正式签名（M5）从环境变量读取：密钥文件不入库，CI 由 GitHub Secrets 注入
    // （见 .github/workflows/release.yml）；未配置时 release 仍可构建（未签名），
    // 正式发布必须走 release.yml（推 tag 触发）。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val releaseStoreFile = System.getenv("AZ_RELEASE_STORE_FILE")
        if (!releaseStoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("AZ_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("AZ_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("AZ_RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.az.notes"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 独立包名 .debug + 内置调试密钥签名：与正式版共存安装、互不覆盖
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 先不混淆，避免反射/序列化被裁剪（后续评估 R8）；
            // Demo 阶段通过 debug APK side-load，正式渠道仅 Release。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式签名：仅当环境变量提供了密钥时应用（默认未签名，发布走 release.yml）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room 建表方案导出目录 + 增量编译（KSP 顶层配置）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // --- AndroidX 基础 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // --- Compose (BOM) ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // --- 生命周期 / 导航 ---
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // --- 数据 ---
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // --- 序列化 (JSON) ---
    // 仓库注册表 / per-vault 同步配置的持久化
    implementation(libs.kotlinx.serialization.json)

    // --- 后台任务 ---
    implementation(libs.androidx.work.runtime.ktx)

    // --- DI ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // --- 图片 ---
    implementation(libs.coil.compose)
    // 预览页网络图片：走受控 OkHttp 客户端（见 NetworkImageGuard：体积上限 / 超时 / 不跟随重定向）
    implementation(libs.coil.network.okhttp)
    // 导入压缩时按 EXIF 方向旋转（相册照片）
    implementation(libs.androidx.exifinterface)

    // --- Markdown 渲染 ---
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // AST 解析（大纲/块锚点）：与渲染库同一解析链
    implementation(libs.jetbrains.markdown)

    // --- 同步（M3：WebDAV / OkHttp）---
    implementation(libs.okhttp)

    // --- 测试 ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 网络图片护栏（体积上限 / 超时 / 不跟随重定向）的本地桩服务测试
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.junit)
}
