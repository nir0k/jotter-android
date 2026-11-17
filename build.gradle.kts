// The Kotlin Gradle plugin on the build classpath pins the Kotlin version used by the Android
// Gradle plugin's built-in Kotlin support and provides the JVM plugin of the core module.
buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}") } }

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}
