import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/** A plain Kotlin module without Android, tested on the JVM, such as core. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    /** Applies the Kotlin JVM plugin with JDK 17 bytecode. */
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureKotlin()
    }
}
