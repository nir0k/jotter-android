plugins {
    alias(libs.plugins.jotter.android.library)
}

android { namespace = "io.github.nir0k.jotter.designsystem" }

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
