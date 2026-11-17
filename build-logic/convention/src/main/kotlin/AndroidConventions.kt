import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** The SDK levels of every module: Android 16 as the minimum, the newest stable SDK to compile and target. */
internal object Sdk {
    const val COMPILE = 37
    const val MIN = 36
    const val TARGET = 37
}

/**
 * Applies what every Android module of Jotter shares: the SDK levels, JDK 17 bytecode, Compose, and
 * unit tests that run on Robolectric with the module's resources.
 *
 * @param android The module's Android extension.
 */
internal fun Project.configureAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = Sdk.COMPILE
        defaultConfig.minSdk = Sdk.MIN
        buildFeatures.compose = true
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.isIncludeAndroidResources = true
        lint.lintConfig = rootProject.file("lint.xml")
    }
    val hostSqlite = hostSqliteTask()
    android.testOptions.unitTests.all {
        // Robolectric's SDK 36 runtime reaches into this JDK package.
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        it.dependsOn(hostSqlite)
        it.systemProperty("java.library.path", hostSqlite.get().destinationDir.path)
    }
    configureKotlin()
}

/**
 * Robolectric runs unit tests on the host's JVM, where the Android build of SQLite's native library
 * cannot load; the tests take the host's library from the JVM build of the same version.
 *
 * @return The task that unpacks the host's library into the module's build folder.
 */
private fun Project.hostSqliteTask() = run {
    val version = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("sqlite").get().requiredVersion
    val hostSqlite = configurations.create("hostSqlite") {
        isTransitive = false
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME)) }
    }
    dependencies.add(hostSqlite.name, "androidx.sqlite:sqlite-bundled-jvm:$version")
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
    val platform = when {
        "mac" in os -> "osx_arm64"
        "windows" in os -> "windows_x64"
        arm -> "linux_arm64"
        else -> "linux_x64"
    }
    tasks.register<Sync>("unpackHostSqlite") {
        from({ hostSqlite.map(::zipTree) }) {
            include("natives/$platform/*")
            eachFile { path = name }
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("host-sqlite"))
    }
}

/** Compiles the module's Kotlin to JDK 17 bytecode, matching its Java code. */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinJvmCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
}
