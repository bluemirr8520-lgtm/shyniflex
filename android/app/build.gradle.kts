import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingRoot = providers.gradleProperty("shyniflexSigningRoot").orNull?.let { file(it) } ?: rootProject.rootDir
val releaseProperties = Properties().apply {
    val source = File(signingRoot, ".local/release.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
// A per-device APK carries only its native runtime; the default remains universal.
val runtimeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val targetAbi = providers.gradleProperty("shyniflexTargetAbi").orNull
require(targetAbi == null || targetAbi in runtimeAbis) { "Unsupported shyniflexTargetAbi" }

android {
    namespace = "io.mirr.shynigsy"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.mirr.shyniflex"
        minSdk = 26
        targetSdk = 36
        versionCode = 57
        versionName = "1.0.56"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += targetAbi?.let { listOf(it) } ?: runtimeAbis }
    }
    signingConfigs {
        create("shyniflexRelease") {
            if (releaseProperties.isNotEmpty()) {
                storeFile = File(signingRoot, releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // Keep synthetic device verification separate from an installed debug app's account data.
            applicationIdSuffix = providers.gradleProperty("shyniflexDebugApplicationIdSuffix").orElse(".debug").get()
            versionNameSuffix = "-test"
        }
        release {
            signingConfig = signingConfigs.getByName("shyniflexRelease")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    // Only the isolated loopback fixture app may use a local HTTP test server.
    if (providers.gradleProperty("shyniflexDebugApplicationIdSuffix").orNull == ".spoilerqa") {
        sourceSets.getByName("debug").manifest.srcFile("src/spoilerqa/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.4")
    val media3Version = "1.10.1"
    // Media3 supplies the existing controls and Player interface. libmpv owns decoding.
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
