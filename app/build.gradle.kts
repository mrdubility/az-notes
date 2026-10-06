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

    // debug 与 release 统一签名：提供 AZ_RELEASE_* 环境变量时两者都用正式密钥
    // （密钥不入库，CI 经 GitHub Secrets 注入，见 .github/workflows/build.yml 与 release.yml），
    // 使 debug/release 包可以互相覆盖安装、真机调试与日常使用数据无缝衔接；
    // 未提供环境变量时回退仓库内置调试密钥（keystore/debug.keystore，公知密码 android）。
    //
    // 正式发布必须走 release.yml（推 tag 触发）；workflow_dispatch 仅产 Actions 产物。
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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 同包名（不设 applicationIdSuffix）+ 同签名：可直接覆盖安装 release 版
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // --- 后台任务 ---
    implementation(libs.androidx.work.runtime.ktx)

    // --- DI ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // --- 图片 ---
    implementation(libs.coil.compose)

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
    androidTestImplementation(libs.androidx.test.junit)
}
