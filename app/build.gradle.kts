
import java.io.ByteArrayOutputStream

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
        include("arm64-v8a", "armeabi-v7a")
        isUniversalApk = true
    }
}

    signingConfigs {
        create("stableDebug") {
            storeFile = file(
                System.getProperty("user.home") +
                    "/.android/debug.keystore"
            )
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
            include("**/*universal*.apk")
        }.files.firstOrNull()
            ?: error("Universal Debug APK not found")

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

        println("UNIVERSAL APK CERTIFICATE:")
        println(output.toString())
    }
}

dependencies {
    implementation(files("libs/libv2ray.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"
    )

    implementation(
        platform("androidx.compose:compose-bom:2024.12.01")
    )

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
