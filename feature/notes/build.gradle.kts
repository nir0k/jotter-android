plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.feature.notes" }

// The home screen, the note lists, search and the trees of collections and tags.
dependencies {
    implementation(project(":ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
}
