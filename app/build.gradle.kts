plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chihaya.mu3androidtx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chihaya.overbite2"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Java/Kotlin のコンパイルターゲットを 1.8 に統一する。
    // 注: Android Gradle Plugin 8.7 系の実行には Android Studio 同梱の JDK 17 が必要。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

// Kotlin 側も Java 側と同じ JVM 1.8 に固定する。
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    // Android USB Host API 上で USBシリアルを扱うためのライブラリ
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")

    // 保存地点の無線スロット符号化はJVM単体テストで検証する（実機不要）。
    // Gradle 9 の既定は JUnit Platform なので Jupiter を使う。
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
