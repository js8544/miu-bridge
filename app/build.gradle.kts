import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties in the project root (git-ignored), holding storeFile,
// storePassword, keyAlias and keyPassword. Without that file a release build is debug-signed.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { stream -> load(stream) } } }

android {
    namespace = "dev.miubridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.miubridge"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
        buildConfigField("String", "RELAY_URL", "\"${providers.gradleProperty("relayUrl").getOrElse("https://phone.jinshang.ai")}\"")
        // The GitHub repository whose releases the app checks for a newer version; empty turns the check off.
        buildConfigField("String", "RELEASES_REPO", "\"${providers.gradleProperty("releasesRepo").getOrElse("js8544/miu-bridge")}\"")
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("org.bouncycastle:bctls-jdk18on:1.79")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
