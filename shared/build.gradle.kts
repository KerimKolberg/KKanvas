import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code shared by the phone app (android target) and the Windows app (desktop target). See DESKTOP.md.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        // expect/actual classes (MediaUri, PlatformBitmap, ...) are still Beta in Kotlin.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // The shared tests run on the desktop JVM and, as their own test app, on the phone (so the same
    // picture tests prove both draw alike). Android's classes, like Uri, only exist on a device, so
    // there are no Android tests on the computer.
    androidLibrary {
        namespace = "com.squareify.shared"
        compileSdk = 37
        minSdk = 31
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.mp.runtime)
            implementation(libs.mp.foundation)
            implementation(libs.mp.ui)
            implementation(libs.mp.material3)
            implementation(libs.mp.icons.extended)
            implementation(libs.kotlinx.coroutines.core)
            // Saved projects and looks are JSON; part of the shared code's API.
            api(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // Pickers and the back button on the phone.
            implementation(libs.androidx.activity.compose)
        }
        val desktopTest by getting {
            dependencies {
                // Skia's native library, so pictures can be drawn in the tests (Windows, or Linux on GitHub).
                implementation(compose.desktop.currentOs)
            }
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Compose's own resource system isn't used here (fonts and artwork are plain resources), and its
// asset copying for the phone test app isn't wired up with this Android Gradle plugin.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach {
    enabled = false
}
