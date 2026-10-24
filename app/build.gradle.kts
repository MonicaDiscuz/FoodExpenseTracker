import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Release 签名配置：
 * 若项目根目录存在 keystore.properties（可由 tools/make-keystore.ps1 生成），
 * 则用它给 release 包正式签名；否则退回 debug 签名，保证 assembleRelease 产出的 APK 可直接安装。
 *
 * 说明：华为 / 鸿蒙设备从第三方渠道安装 debug 签名的 APK 会提示"应用是非正式发布版本"，
 * 因此要装到真机上分享/分发时，请用正式签名包（assembleRelease）。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystoreFile = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
val hasReleaseKeystore = releaseKeystoreFile != null && releaseKeystoreFile.exists()

android {
    namespace = "com.example.foodexpense"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.foodexpense"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // ML Kit 中文 OCR 的 native 库每个 ABI 约 7~12MB，四份共 41MB。
            // 只保留 arm64-v8a：覆盖 2015 年后的绝大多数真机。
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // 鸿蒙 / Android 12+ 设备建议开启 v2/v3 签名
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式 keystore 就用正式签名；没有则退回 debug 签名（仍可安装，但部分渠道会提示非正式版本）
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 开启后才能用 BuildConfig.DEBUG 隐藏仅调试用的入口（识图 POC 页）
        buildConfig = true
    }

    /**
     * 本机单元测试配置：
     * - isIncludeAndroidResources：让 Robolectric 能读到 res / assets
     * - isReturnDefaultValues：android.jar 里的桩方法返回默认值而不是抛异常
     * 解析器（ocr 包下的纯 Kotlin 类）不需要任何 Android 类，仍可纯 JVM 直跑。
     */
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 端上 OCR：中文脚本识别（bundled 模型，离线可用，不需要 INTERNET 权限）
    implementation(libs.mlkit.text.recognition.chinese)

    // API key 加密存储
    implementation(libs.androidx.security.crypto)

    // 单元测试：解析器为纯 JVM 代码
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.ui.tooling)
}
