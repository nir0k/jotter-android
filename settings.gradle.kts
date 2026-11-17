pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jotter-android"

include(":app", ":core", ":editor", ":designsystem", ":data", ":ui")
include(":feature:notes", ":feature:note", ":feature:settings", ":feature:sync")
