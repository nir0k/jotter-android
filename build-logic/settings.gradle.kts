// The convention plugins that hold the build settings the modules share.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
include(":convention")
