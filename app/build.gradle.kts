import java.util.Properties

plugins {
    alias(libs.plugins.jotter.android.application)
    alias(libs.plugins.roborazzi)
}

// The product version lives in the root VERSION file; the version code is derived from it.
val productVersion = rootProject.file("VERSION").readText().trim()
val versionParts = productVersion.split(".").map(String::toInt)
require(versionParts.size == 3 && versionParts.all { it >= 0 } && versionParts.drop(1).all { it < 1000 }) {
    "VERSION must be major.minor.patch with minor and patch below 1000"
}

// Release signing comes from keystore.properties or JOTTER_ANDROID_ variables; without either the
// release build is left unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingValue(key: String, variable: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(variable)
val releaseStoreFile = signingValue("storeFile", "JOTTER_ANDROID_KEYSTORE_FILE")

android {
    namespace = "io.github.nir0k.jotter"
    defaultConfig {
        applicationId = "io.github.nir0k.jotter"
        versionCode = versionParts[0] * 1000000 + versionParts[1] * 1000 + versionParts[2]
        versionName = productVersion
        // The native SQLite ships for these only: no Android 16 device runs on 32-bit x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    androidResources { localeFilters += listOf("en", "ru") }
    // The language is chosen in the application, so every language ships in the base.
    bundle { language { enableSplit = false } }
    // The Bouncy Castle jars carry the same licence and OSGi files; the licences show in About.
    packaging { resources { excludes += listOf("META-INF/LICENSE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "JOTTER_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "JOTTER_ANDROID_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "JOTTER_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":editor"))
    implementation(project(":designsystem"))
    implementation(project(":data"))
    implementation(project(":ui"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:note"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:sync"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
}
