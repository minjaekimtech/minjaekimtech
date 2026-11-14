import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 서명 키(선택): 프로젝트 루트의 keystore.properties (git에 올리지 않음)
//   storeFile=../deulleo-release.jks
//   storePassword=...
//   keyAlias=deulleo
//   keyPassword=...
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Pleos Playground My Project 자격 증명(선택): local.properties (git에 올리지 않음)
//   pleos.clientId=...
//   pleos.clientSecret=...
// 비어 있으면 STT/TTS를 차량 On-device 엔진으로 사용합니다.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "ai.deulleo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.deulleo.app"
        minSdk = 28 // Pleos Connect 요구사항: API 28 이상
        targetSdk = 34 // Pleos Connect Emulator v2.1.0 = API 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localProps.getProperty("pleos.clientId", "")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localProps.getProperty("pleos.clientSecret", "")}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 서명 키가 없으면 디버그 키로 서명해 바로 설치 가능한 release APK를 만듭니다.
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core"))

    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    // UI
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
