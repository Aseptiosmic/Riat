plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.riat.lyane"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.riat.lyane"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // sherpa-onnx native kütüphaneleri dört ABI içerir; APK'ları ABI başına
    // ayırarak boyutu küçültürüz (universal APK da üretilir).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // LyLog android.util.Log kullanır; JVM testlerinde sessiz varsayılan dön
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ── Lyane konuşma motoru: sherpa-onnx (k2-fsa) ────────────────────────
    // AAR, settings.gradle.kts içindeki ivy deposu üzerinden GitHub
    // Release'lerinden otomatik indirilir (ilk derlemede internet gerekir).
    // @aar: ivy deseninde [ext] = aar olarak çözülür
    implementation("com.k2-fsa:sherpa-onnx:${libs.versions.sherpaOnnx.get()}@aar")

    // Çevrimdışı alternatif: AAR'ı
    // https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
    // adresinden indirip app/libs/ klasörüne koyun, sonra:
    // implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Eklenti sistemi: Rhino (saf Java JavaScript motoru)
    implementation(libs.mozilla.rhino)
    // Model arşivi çıkarma (tar.bz2 / tar.gz)
    implementation(libs.apache.commons.compress)
    // Lyane Drop: yerleşik HTTP sunucusu
    implementation(libs.nanohttpd)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
