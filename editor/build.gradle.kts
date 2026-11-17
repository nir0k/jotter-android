plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.editor" }

dependencies {
    implementation(project(":core"))
    // The editor's controls follow the design system: its glyphs, sizes and theme roles.
    implementation(project(":designsystem"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Typing into the editor's fields in tests.
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
