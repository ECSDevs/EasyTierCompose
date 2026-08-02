plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

// Semantic version from version.txt (configuration-cache safe: lazy provider).
val semanticVersion: Provider<String> = providers.fileContents(
    rootProject.layout.projectDirectory.file("version.txt")
).asText.map { it.trim() }.filter { it.isNotEmpty() }.orElse("0.0.0")

// Git commit count as numeric version code (configuration-cache safe:
// providers.exec is tracked as a build input and evaluated lazily).
val commitCount: Provider<Int> = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map {
    it.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
}

android {
    namespace = "cc.ptoe.easytier.compose"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "cc.ptoe.easytier.compose"
        minSdk = 24
        targetSdk = 36
        // Placeholders; real values are wired per-variant via androidComponents
        // below so the git invocation stays configuration-cache compatible.
        versionCode = 1
        versionName = "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Wire versionCode (commit count) and versionName (semantic version) per-variant
// output using lazy providers, so the build is configuration-cache compatible and
// Android Studio / CLI share the same source of truth without extra flags.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(commitCount)
            output.versionName.set(semanticVersion)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsu.core)
    implementation(libs.kotlin.parcelize.runtime)
    implementation(libs.libsu.service)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}