plugins {
    `kotlin-dsl`
}

// The plugins themselves are put on the build's classpath by the root project; here they are only
// compiled against.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "jotter.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "jotter.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "jotter.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
