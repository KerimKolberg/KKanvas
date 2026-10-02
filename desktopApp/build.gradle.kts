import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app. Almost everything comes from :shared; this is the window and the packaging.
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
}

compose.desktop {
    application {
        mainClass = "com.squareify.desktop.MainKt"
        // A full JDK (with jpackage) to run and package the app: DESKTOP_JDK, else the one running Gradle.
        System.getenv("DESKTOP_JDK")?.let { javaHome = it }
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "kk-Squareify"
            packageVersion = "1.1.0"
            vendor = "kk"
            windows {
                menu = true
                shortcut = true
            }
        }
    }
}
