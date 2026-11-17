plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.ui" }

// What the feature screens share: frames, dialogs, item styles, file handling and the export dialog.
dependencies {
    api(project(":data"))
    api(project(":designsystem"))
    // The export dialog names the editor's texts; note files are resolved as the editor resolves them.
    implementation(project(":editor"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
