plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.feature.sync" }

// The Sync tab of Settings and the setup of an SFTP connection.
dependencies {
    implementation(project(":ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
