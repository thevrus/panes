import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
android {
    namespace = "dev.vr.panes"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.vr.panes"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Both come from the gitignored local.properties. Defaults are the publishable build: no server, and the
        // YouTube player shown at once with nothing in front of it. `panes.personal=true` restores the owner's build.
        buildConfigField("String", "SERVER", "\"${local.getProperty("panes.server", "")}\"")
        buildConfigField("boolean", "PERSONAL", local.getProperty("panes.personal", "false"))
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // 1.9.x is the newest line that still builds against compileSdk 35 / AGP 8.7.
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.4")
}
