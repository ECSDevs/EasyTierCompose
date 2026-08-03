import java.io.StringReader
import java.util.Properties

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

// Signing credentials for the release variant. CI passes env vars
// (KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) decoded
// from GitHub secrets; local builds use a sibling keystore.properties file.
// Configuration-cache compatible: file content is read via providers.fileContents
// (tracked build input, evaluated lazily), env vars via providers.environmentVariable.
val keystoreProps: Provider<Properties> = providers.fileContents(
    rootProject.layout.projectDirectory.file("keystore.properties")
).asText.map { txt: String ->
    Properties().apply { load(StringReader(txt)) }
}.orElse(Properties())

val releaseStoreFile: Provider<String> = providers.environmentVariable("KEYSTORE_PATH")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("storeFile") ?: "" })
val releaseStorePassword: Provider<String> = providers.environmentVariable("KEYSTORE_PASSWORD")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("storePassword") ?: "" })
val releaseKeyAlias: Provider<String> = providers.environmentVariable("KEY_ALIAS")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("keyAlias") ?: "" })
val releaseKeyPassword: Provider<String> = providers.environmentVariable("KEY_PASSWORD")
    .orElse(keystoreProps.map { props: Properties -> props.getProperty("keyPassword") ?: "" })
val hasSigningCredentials: Provider<Boolean> = releaseStoreFile
    .map { path: String -> path.isNotBlank() }
    .orElse(false)

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
    signingConfigs {
        create("release") {
            // Only populate when credentials resolve (env vars on CI, or
            // keystore.properties locally). When absent, the release variant
            // falls back to the debug signing config so builds still succeed.
            if (hasSigningCredentials.get()) {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }
    buildTypes {
        release {
            // Attach the release signing config when credentials are available;
            // otherwise keep AGP's default (debug) signing so local builds
            // without the keystore still produce an installable APK.
            if (hasSigningCredentials.get()) {
                signingConfig = signingConfigs.getByName("release")
            }
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