import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. build.ps1 generates the keystore and this file on first
// -Release build. Deliberately NOT local.properties, which build.ps1 rewrites
// wholesale every run and would silently discard these values.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.let { File(it).exists() } == true

android {
    namespace = "com.kosta.glyphbar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kosta.glyphbar"
        // The Glyph SDK .aar declares minSdkVersion 33, but the GDK requires
        // Nothing OS on Android 14+. Phone (4a) ships well above this.
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off deliberately: the Glyph SDK is a closed-source .aar
            // reached partly through a binder proxy, and shrinking risks stripping
            // classes we cannot easily verify are kept. Size is not a concern here.
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
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
    }
}

dependencies {
    // Vendored from Nothing-Developer-Programme/Glyph-Developer-Kit (sdk/glyph-matrix-sdk-2.0.aar).
    // Not published to Maven — it must live in app/libs.
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
