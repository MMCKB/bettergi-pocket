plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zenlesszonezero.pocket"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zenlesszonezero.pocket"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        // 统一签名：仓库内置固定 keystore，保证所有构建的签名一致（可覆盖安装/升级）
        create("unified") {
            storeFile = file("zzz-pocket.jks")
            storePassword = "zzzpocket2026"
            keyAlias = "zzzpocket"
            keyPassword = "zzzpocket2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("unified")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("unified")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.opencv)
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
    val desktopOpenCv = file("libs/opencv-4.9.0-0.jar")
    if (desktopOpenCv.exists()) {
        testImplementation(files(desktopOpenCv))
    } else {
        testImplementation(libs.opencv.desktop)
    }
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

configurations.matching {
    val n = name.lowercase()
    n.contains("test") && n.contains("classpath") && !n.contains("androidtest")
}.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}
