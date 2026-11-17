import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** An Android library module with Compose, such as the design system or the editor. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    /** Applies the Android library and Compose compiler plugins with the shared settings. */
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<LibraryExtension> { configureAndroid(this) }
    }
}
