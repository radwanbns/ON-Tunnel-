import java.io.ByteArrayOutputStream
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val configuredAbis = (findProperty("ABI_FILTERS") as? String)
    ?.split(";")
    ?.filter { it.isNotBlank() }
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val makeUniversalApk = (findProperty("UNIVERSAL_ONLY") as? String)?.toBoolean() ?: true

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

        // Ship ARM and Intel Android architectures.
        ndk {
            abiFilters.clear()
            abiFilters.addAll(configuredAbis)
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
            include(*configuredAbis.toTypedArray())
            isUniversalApk = makeUniversalApk
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

    packaging {
        jniLibs {
            // ICS OpenVPN executes its bundled native OpenVPN binary from nativeLibraryDir.
            useLegacyPackaging = true
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
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
    implementation(files("libs/ics-openvpn.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
