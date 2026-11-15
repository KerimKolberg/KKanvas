// AGP 9 compiles Kotlin itself, so there is no separate kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The private key the app is signed with. It isn't in the repository: its place and password are
// in ~/.gradle/gradle.properties on the PC and in the repository's secrets on GitHub. Whoever
// builds without it (a fork, a pull request) gets the checked-in debug key instead, which can't
// update an app signed with the private one.
val privateKeystore = ((findProperty("kkanvasKeystore") as String?) ?: System.getenv("KKANVAS_KEYSTORE"))
    ?.takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.exists() }
val privateKeystorePassword = ((findProperty("kkanvasKeystorePassword") as String?) ?: System.getenv("KKANVAS_KEYSTORE_PASSWORD"))
    ?.takeIf { it.isNotBlank() }

android {
    namespace = "com.squareify.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.squareify.app"
        minSdk = 31
        // Kept at 34 like the original; 35+ forces edge-to-edge layout.
        targetSdk = 34
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersion.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Debug and release builds are signed alike, so either can update the other on the phone.
        getByName("debug") {
            if (privateKeystore != null && privateKeystorePassword != null) {
                storeFile = privateKeystore
                storePassword = privateKeystorePassword
                keyAlias = "kkanvas"
                keyPassword = privateKeystorePassword
            } else {
                // The standard Android debug key, public like the rest of the repository.
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // The build for the phone: shrunk and optimised, so it's much smaller and quicker than debug.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
