
import java.io.ByteArrayOutputStream
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ontunnel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ontunnel.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(
                *((project.findProperty("ABI_FILTERS") as? String)
                    ?.split(";")?.toTypedArray()
                    ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
            )
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Real release signing. NEVER reuses the debug keystore above.
        // Resolved from environment variables (CI, via GitHub Secrets) or
        // from gradle.properties (local dev – gitignored, never commit it).
        // If none of these are set the release build type below simply
        // falls back to no signing config, producing an unsigned APK rather
        // than silently signing with a weak/shared key.
        create("release") {
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
                ?: (findProperty("RELEASE_STORE_FILE") as? String)
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: (findProperty("RELEASE_STORE_PASSWORD") as? String)
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: (findProperty("RELEASE_KEY_ALIAS") as? String)
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: (findProperty("RELEASE_KEY_PASSWORD") as? String)
            }
        }
    }

    buildTypes {
        // Development / CI smoke-test builds ONLY. android:debuggable=true
        // is implied by this build type, which makes `adb shell run-as`,
        // JDWP debugger attach, and most generic smali-patching tool-chains
        // (MT Manager, NP Manager, etc.) dramatically easier. NEVER
        // distribute this build type to end users.
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // This is what must ship to end users.
        release {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.register("printDebugSha256") {
    dependsOn("assembleDebug")

    doLast {
        val apk = fileTree("build/outputs/apk/debug") {
            include("**/*.apk")
        }.files.firstOrNull()
            ?: error("Debug APK not found")

        val output = ByteArrayOutputStream()

        project.exec {
            commandLine(
                "keytool",
                "-printcert",
                "-jarfile",
                apk.absolutePath
            )
            standardOutput = output
        }

        println("DEBUG APK CERTIFICATE:")
        println(output.toString())
    }
}

dependencies {
    implementation(files("libs/libv2ray.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
