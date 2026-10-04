import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself (built-in Kotlin); only the Compose compiler plugin is needed.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "computer.handy.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "computer.handy.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            // Phones are arm64; x86_64 keeps the emulator usable. Drops ~25 MB of 32-bit libs.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        resources {
            // Duplicate metadata from the Anthropic SDK's JVM dependencies.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// sherpa-onnx (on-device speech recognition) is only published as a GitHub release AAR.
// Downloaded once into app/libs (git-ignored) and checked against a pinned SHA-256.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

fun downloadFollowingRedirects(source: String, target: File) {
    var url = URI(source).toURL()
    repeat(5) {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        try {
            when (val code = conn.responseCode) {
                in 300..399 -> url = URI(conn.getHeaderField("Location")).toURL()
                200 -> {
                    conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                    return
                }
                else -> error("Download of $source failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
    error("Too many redirects for $source")
}

if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    logger.lifecycle("Downloading sherpa-onnx $sherpaVersion AAR")
    sherpaAar.parentFile.mkdirs()
    downloadFollowingRedirects(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar",
        sherpaAar,
    )
    check(sha256(sherpaAar) == sherpaSha256) {
        sherpaAar.delete()
        "sherpa-onnx AAR checksum mismatch"
    }
}

// Silero VAD, the same model desktop Handy uses, bundled as an asset (≈0.6 MB).
val sileroVad = layout.projectDirectory.file("src/main/assets/silero_vad.onnx").asFile
val sileroSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
if (!sileroVad.exists() || sha256(sileroVad) != sileroSha256) {
    logger.lifecycle("Downloading Silero VAD")
    sileroVad.parentFile.mkdirs()
    downloadFollowingRedirects(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        sileroVad,
    )
    check(sha256(sileroVad) == sileroSha256) {
        sileroVad.delete()
        "Silero VAD checksum mismatch"
    }
}

dependencies {
    implementation(files(sherpaAar))
    implementation(libs.anthropic.java)
    implementation(libs.commons.compress)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
