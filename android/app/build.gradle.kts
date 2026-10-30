plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yujh.todaymarket"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yujh.todaymarket"
        minSdk = 26
        targetSdk = 35
        // GitHub 빌드 번호를 버전으로 (새 버전을 덮어 설치할 수 있게)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "0.1.$run"
    }

    // 매번 같은 열쇠로 서명해야 새 버전을 지우지 않고 덮어 설치할 수 있다
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // 목록 순서 바꾸기 (꾹 눌러 끌기 + 화면 끝에서 자동 스크롤)
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
}
