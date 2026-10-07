import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app. Almost everything comes from :shared; this is the window, Windows' side of
// files, video (FFmpeg) and the Recycle Bin, and the packaging.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.mp.material3)
    implementation(libs.mp.icons.extended)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

/** FFmpeg for Windows (gyan.dev build with AMD AMF): FFMPEG_DIR, else tools/ffmpeg beside the repository. */
val ffmpegDir = System.getenv("FFMPEG_DIR")?.let(::File) ?: rootDir.resolve("../tools/ffmpeg")

/** FFmpeg goes into the app folder, next to the app; Compose finds it there at run time. */
val appResources = layout.buildDirectory.dir("appResources")
val copyFfmpeg by tasks.registering(Sync::class) {
    from(ffmpegDir.resolve("bin")) { include("ffmpeg.exe", "ffprobe.exe") }
    from(ffmpegDir) { include("LICENSE", "README.txt") }
    into(appResources.map { it.dir("windows/ffmpeg") })
}

tasks.withType<Test>().configureEach {
    systemProperty("kk.ffmpeg.dir", ffmpegDir.resolve("bin").path)
}

compose.desktop {
    application {
        mainClass = "com.squareify.desktop.MainKt"
        // A full JDK (with jpackage) to run and package the app: DESKTOP_JDK, else the one running Gradle.
        System.getenv("DESKTOP_JDK")?.let { javaHome = it }
        jvmArgs += listOf("-Xmx6g")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "kkanvas"
            // The phone's version (Windows installers want three numbers).
            packageVersion = "${libs.versions.appVersion.get()}.0"
            description = "Pads, edits and builds Instagram posts"
            vendor = "kk"
            appResourcesRootDir.set(appResources)
            modules("java.desktop", "java.naming", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                menuGroup = "kkanvas"
                shortcut = true
                dirChooser = true
                // Installs for this user only: no administrator question, updates in place.
                perUserInstall = true
                upgradeUuid = "6b1d3c3e-8f0a-4a8e-9d2b-5f3c1a7e2b90"
            }
        }
    }
}

tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "packageExe", "packageMsi", "run") }.configureEach {
    dependsOn(copyFfmpeg)
}

// Run from the source (gradlew :desktopApp:run): FFmpeg straight from its folder. The packaged app
// finds the copy in its own folder instead.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("kk.ffmpeg.dir", ffmpegDir.resolve("bin").path)
}
