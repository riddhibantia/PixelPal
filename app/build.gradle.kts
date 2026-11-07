import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// ---------------------------------------------------------------------------
// Versions are never guessed.
//
// Android decides whether an update may install over an existing copy by
// versionCode, and stores the versionName in crash records/support threads.
// A silent default (the old `getOrElse(3)` / `"1.2.0"`) produces artifacts that
// either cannot be installed or misreport themselves, so:
//   * a real release build must be given both values (CI derives them from the
//     v* tag — see RELEASING.md);
//   * a local debug build derives a monotonic dev version from git.
// `-PPIXELPAL_RELEASE_PRECHECK=true` is the CI-only escape hatch that builds an
// unsigned, dev-versioned release to exercise R8.
// ---------------------------------------------------------------------------
fun git(vararg args: String): String? = try {
    providers.exec {
        commandLine(listOf("git") + args.toList())
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

val releasePrecheck = providers.gradleProperty("PIXELPAL_RELEASE_PRECHECK")
    .map { it.toBoolean() }
    .getOrElse(false)
val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("elease") }
val isStampedRelease = isReleaseTask && !releasePrecheck

val versionCodeProp = providers.gradleProperty("PIXELPAL_VERSION_CODE")
val versionNameProp = providers.gradleProperty("PIXELPAL_VERSION_NAME")

if (isStampedRelease && (!versionCodeProp.isPresent || !versionNameProp.isPresent)) {
    throw GradleException(
        "A release build needs an explicit version. Pass " +
            "-PPIXELPAL_VERSION_CODE=<n> -PPIXELPAL_VERSION_NAME=<x.y.z> " +
            "(CI derives both from the v* tag). For an unsigned R8 pre-check run " +
            "-PPIXELPAL_RELEASE_PRECHECK=true."
    )
}

/** Monotonic, above the release range (MAJOR*10000+MINOR*100+PATCH ≈ 10300). */
val devVersionCode = 900_000 + (git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1)
val devVersionName = "dev-" + (
    git("describe", "--tags", "--always", "--dirty")
        ?.removePrefix("v")
        ?.replace(Regex("[^0-9A-Za-z._-]"), "-")
        ?: "local"
    )

/** One source for the repo the in-app install instructions point at. */
val repoSlug = providers.gradleProperty("PIXELPAL_REPO_SLUG")
    .getOrElse("riddhibantia/PixelPal")

android {
    namespace = "com.pixelpal.app"
    compileSdk = 35

    val localProps = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
    val geminiApiKey = localProps.getProperty("GEMINI_API_KEY")
        ?: providers.gradleProperty("GEMINI_API_KEY").orNull
        ?: providers.environmentVariable("GEMINI_API_KEY").orNull
        ?: ""
    // A key compiled into a shipped APK is a key anyone can extract and spend.
    // Release builds therefore carry no key unless the maintainer opts in with
    // -PPIXELPAL_EMBED_GEMINI_KEY=true; the app accepts a user-supplied key in
    // the Agent tab and falls back to local dialogue when there is none.

    defaultConfig {
        applicationId = "com.pixelpal.app"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeProp.map { it.toInt() }.getOrElse(devVersionCode)
        versionName = versionNameProp.getOrElse(devVersionName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "REPO_SLUG", "\"$repoSlug\"")
        buildConfigField(
            "String",
            "BRIDGE_INSTALL_URL",
            "\"https://github.com/$repoSlug/releases/latest/download/install.sh\""
        )
        buildConfigField(
            "String",
            "BRIDGE_RELEASES_URL",
            "\"https://github.com/$repoSlug/releases\""
        )
    }

    signingConfigs {
        create("release") {
            val keystorePath = providers.gradleProperty("RELEASE_STORE_FILE")
            if (keystorePath.isPresent && keystorePath.get().isNotBlank()) {
                storeFile = file(keystorePath.get())
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").getOrElse("")
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").getOrElse("")
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").getOrElse("")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ship no Gemini key unless explicitly asked to.
            val embedKey = providers.gradleProperty("PIXELPAL_EMBED_GEMINI_KEY")
                .map { it.toBoolean() }
                .getOrElse(false)
            buildConfigField("String", "GEMINI_API_KEY", "\"${if (embedKey) geminiApiKey else ""}\"")

            val hasReleaseStore = providers.gradleProperty("RELEASE_STORE_FILE")
                .map { it.isNotBlank() }
                .getOrElse(false)
            if (hasReleaseStore) {
                signingConfig = signingConfigs.getByName("release")
            } else if (isStampedRelease) {
                // An unsigned release installs nowhere and can never be updated
                // over, so refuse to produce one by accident.
                throw GradleException(
                    "A release build must be signed. Pass the four " +
                        "-PRELEASE_STORE_FILE/-PRELEASE_STORE_PASSWORD/-PRELEASE_KEY_ALIAS/" +
                        "-PRELEASE_KEY_PASSWORD values (see RELEASING.md), or run " +
                        "-PPIXELPAL_RELEASE_PRECHECK=true for an unsigned R8 check."
                )
            }
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // AndroidX Core & Activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Material3 & Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation("androidx.compose.ui:ui-text-google-fonts")

    // Hilt DI
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coil & Lottie
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.lottie.compose)
    implementation(libs.lottie)

    // Gemini AI SDK
    implementation(libs.google.generativeai)

    // Firebase (BOM + Auth + Firestore)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Google Sign-In via Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // CameraX + ML Kit (QR agent pairing)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.guava)

    // Timber Logging
    implementation(libs.timber)

    // Kotlinx Coroutines & Flow
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // OkHttp (agent connector & web sockets)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }
}
