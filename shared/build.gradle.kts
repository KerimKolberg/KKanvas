import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code shared by the phone app (android target) and the Windows app (desktop target). See DESKTOP.md.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // The shared tests run on the desktop JVM; the phone is covered by the app's on-device tests
    // (Android's classes, like Uri, only exist on a device).
    androidLibrary {
        namespace = "com.squareify.shared"
        compileSdk = 37
        minSdk = 31
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
