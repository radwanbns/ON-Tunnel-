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
        abi { isEnable = true; reset(); include(*((project.findProperty("ABI_FILTERS") as? String)?.split(";")?.toTypedArray() ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) ); isUniversalApk = false }
    }
    signingConfigs {
        create("release") {
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("KEY_ALIAS")
            val keyPasswordValue = System.getenv("KEY_PASSWORD")
            storeFile = rootProject.file("release-keystore.jks")
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // CI builds the debug variant, so classes.dex is obfuscated/shrunk there too.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
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
