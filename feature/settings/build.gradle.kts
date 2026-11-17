plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.feature.settings" }

// Settings: appearance and language, libraries, rules, themes, About and the first start.
dependencies {
    implementation(project(":ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
}
