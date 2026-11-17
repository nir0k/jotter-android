plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.feature.note" }

// The note screen: the editor with its formatting bar, dialogs, information and image viewer.
dependencies {
    implementation(project(":ui"))
    implementation(project(":editor"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
