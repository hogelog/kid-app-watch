plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val baseVersionName = "0.1.1"
val gitShortRev: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")
val prNumber: String? = System.getenv("PR_NUMBER")?.takeIf { it.isNotBlank() }
val appVersionName: String = if (prNumber != null) {
    "$baseVersionName-pr-$prNumber-$gitShortRev"
} else {
    baseVersionName
}

// Monotonic versionCode, following the pattern used by the other Android apps:
// MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + commits on main.
// 0.1.1 starts above earlier CI-run-number based builds such as versionCode 24.
val countTip: String = if (prNumber != null) {
    val baseSha = System.getenv("BASE_SHA")?.takeIf { it.isNotBlank() }
    if (baseSha != null) {
        providers.exec {
            commandLine("git", "merge-base", "HEAD", baseSha)
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.getOrElse("").ifEmpty { "HEAD" }
    } else {
        "HEAD"
    }
} else {
    "HEAD"
}
val commitsSinceTag: Int = providers.exec {
    commandLine("git", "rev-list", "--count", countTip)
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }.getOrElse(0)
val appVersionCode: Int = baseVersionName.split(".").let { parts ->
    require(parts.size == 3) { "baseVersionName must be MAJOR.MINOR.PATCH" }
    val (major, minor, patch) = parts.map { it.toInt() }
    major * 1_000_000 + minor * 10_000 + patch * 100 + commitsSinceTag
}

android {
    namespace = "dev.hogelog.kidappwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hogelog.kidappwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Committed debug keystore so debug APKs from different CI runners share a
    // signing identity and can be installed as updates over each other.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
