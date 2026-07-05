plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orElse(providers.environmentVariable("VERSION_CODE"))
    .map(String::toInt)
    .orElse(1)
val ciVersionName = ciVersionCode.map { code -> "0.1.$code" }

android {
    namespace = "dev.hogelog.kidappwatch"
    compileSdk = 36

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "dev.hogelog.kidappwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode.get()
        versionName = ciVersionName.get()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
