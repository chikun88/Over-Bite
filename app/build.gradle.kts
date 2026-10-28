import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

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
        versionCode = 2
        versionName = "1.1"
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

// Windows で日本語を含むパス（例: overbite2.0リリース用）に置くと、Gradle は長い
// テストクラスパスを UTF-8 の @argfile で渡すが、java ランチャーは ANSI コードページ
// (Shift_JIS) で読むためパスが化け、testDebugUnitTest が ClassNotFoundException になる。
// ASCII のみの file: URI を Class-Path に並べた manifest だけの jar 経由で渡して回避する。
tasks.withType<Test>().configureEach {
    val nonAsciiWindowsPath = System.getProperty("os.name").startsWith("Windows") &&
        project.projectDir.absolutePath.any { it.code > 127 }
    if (nonAsciiWindowsPath) {
        // jar 自体も ASCII パスに置く必要があるため、プロジェクト外の Gradle ユーザーホームへ。
        val pathingJarFile = File(
            gradle.gradleUserHomeDir,
            "tmp/test-pathing-jars/${project.projectDir.absolutePath.hashCode().toUInt()}/$name.jar"
        )
        doFirst {
            // JUnit Platform の jar は Gradle が classpath 上で直接探すため、ASCII の
            // エントリはそのまま残し、非ASCIIのエントリだけを pathing jar に移す。
            val (nonAscii, ascii) = classpath.files.partition { f -> f.absolutePath.any { it.code > 127 } }
            val jar = pathingJarFile
            jar.parentFile.mkdirs()
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.CLASS_PATH] =
                    nonAscii.joinToString(" ") { it.toURI().toASCIIString() }
            }
            JarOutputStream(jar.outputStream(), manifest).close()
            classpath = files(jar) + files(ascii)
        }
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
