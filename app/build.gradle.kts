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
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ontunnel.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "1.8"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        // Only ship ARM native libs (exclude x86/x86_64 from AARs like libv2ray)
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(
                *((project.findProperty("ABI_FILTERS") as? String)
                    ?.split(";")?.toTypedArray()
                    ?: arrayOf("arm64-v8a", "armeabi-v7a"))
            )
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        aidl = true
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/lib/x86/**",
                "**/lib/x86_64/**",
                "**/x86/**",
                "**/x86_64/**"
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
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // SSH client (maintained JSch fork) for the SSH tunnel types
    implementation("com.github.mwiede:jsch:0.2.20")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
