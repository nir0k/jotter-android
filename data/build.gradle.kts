plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.data" }

// The Android side of the library: the open library and its index, device preferences, sync runs in
// the background with their notifications and credentials, geotags and exports.
dependencies {
    api(project(":core"))
    // Exports draw notes as the editor does; notifications take the palette's accent.
    implementation(project(":editor"))
    implementation(project(":designsystem"))
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
}
