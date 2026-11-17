import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The Android application module: the shared Android settings and the target SDK. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    /** Applies the Android application and Compose compiler plugins with the shared settings. */
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig.targetSdk = Sdk.TARGET
        }
    }
}
