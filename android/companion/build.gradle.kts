import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// Keystore passwords live with the keystore in .secrets/ (git-ignored), not in this public file.
val signing = Properties()
val signingFile = rootProject.file("../.secrets/signing.properties")
if (signingFile.exists()) signingFile.inputStream().use { signing.load(it) }

// Firebase (push through the PhoneLock hub) and the hub address, from .secrets/firebase.properties if present.
// Without it the app keeps its own background connection, as before.
val firebase = Properties()
val firebaseFile = rootProject.file("../.secrets/firebase.properties")
if (firebaseFile.exists()) firebaseFile.inputStream().use { firebase.load(it) }
fun quoted(key: String) = "\"" + (firebase.getProperty(key) ?: "") + "\""

val gitVersionCode = rootProject.extra["gitVersionCode"] as Int

android {
    namespace = "home.phonelock.parent"
    compileSdk = 37

    // Ship only the app's languages (LOC-10): the nine in res/xml/locales_config.xml, plus the
    // qualifiers libraries use for the same languages (zh-rCN, pt-rBR), so their built-in text matches.
    androidResources {
        localeFilters += listOf("en", "en-rCA", "es", "fr", "fr-rCA", "de", "b+zh+Hans", "zh-rCN", "pt", "pt-rBR", "ja")
    }

    defaultConfig {
        // The company namespace (D-011, BRAND-7); the code's own package, namespace above, stays.
        applicationId = "ca.dynamicsolutions.phonelock.parent"
        minSdk = 33
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = "1.0.$gitVersionCode"
        // On-device tests (companion/src/androidTest), run with connectedGithubDebugAndroidTest on a phone or emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FCM_APP_ID", quoted("appId"))
        buildConfigField("String", "FCM_API_KEY", quoted("apiKey"))
        buildConfigField("String", "FCM_PROJECT_ID", quoted("projectId"))
        buildConfigField("String", "FCM_SENDER_ID", quoted("senderId"))
        buildConfigField("String", "HUB_URL", quoted("hubUrl"))
        // Google Maps key, restricted to this app's package and signing key (.secrets/firebase.properties).
        manifestPlaceholders["mapsApiKey"] = firebase.getProperty("mapsApiKey") ?: ""
    }

    signingConfigs {
        // Debug builds use the committed debug-only key (android/keystore/debug.keystore, standard "android"
        // passwords), so every machine and container signs them the same way and reinstalls work (DIST-8).
        // It is not a secret.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("lock") {
            storeFile = rootProject.file("../.secrets/phonelock.keystore")
            storePassword = signing.getProperty("storePassword")
            keyAlias = "phonelock"
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lock")
        }
        // The production key signs release builds only (SEC-11).
        // Signed with the debug key, a debug parent app cannot update over a release one, and Maps stays
        // blank in it unless the Maps key also allows the debug certificate.
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Where the app is distributed. "github" (the releases on this repo) updates itself from them; "play" is
    // left to the Play Store, which forbids self-updating, so it has no updater or install permission.
    flavorDimensions += "dist"
    productFlavors {
        create("github") { dimension = "dist" }
        create("play") { dimension = "dist" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Play app bundles normally split languages per device; keep them all so a language picked in the app is
    // already installed.
    bundle {
        language { enableSplit = false }
    }

    // Every language must have every string, and the same placeholders, before a release builds
    // (release builds run these checks; see docs/development.md "Languages").
    lint {
        // Issues already in the code when lint joined the gate (validate); new issues fail. Shrink it, never
        // grow it: regenerate with updateLintBaseline only after fixing entries.
        baseline = file("lint-baseline.xml")
        fatal += listOf(
            "MissingTranslation",
            "ExtraTranslation",
            "StringFormatInvalid",
            "StringFormatCount",
            "StringFormatMatches",
            "MissingQuantity",
            "UnusedQuantity",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    // In-app QR scanning: CameraX frames, decoded on the phone by ZXing core below (approved: CameraX)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // Push through the PhoneLock hub (approved: Firebase Cloud Messaging)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // Drawing and scanning setup and pairing QR codes (approved: ZXing core)
    implementation(libs.zxing.core)
    // In-app map of where the device has been (approved: Google Maps SDK; map loads in apps are free).
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)

    // Tests only, never shipped: our SPAKE2 (adb/Spake2.kt) is checked against an independent implementation
    // of the same exchange (GPL, so it must stay out of the app itself).
    testImplementation(libs.junit)
    testImplementation(libs.spake2.java)

    // On-device tests only, never shipped: the QR decoder on Android's own image buffers
    // (approved: standards session for Ryan, test-only (2026-11-19)).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// AGP 9 compiles Kotlin itself (no separate Kotlin Android plugin); options go here.
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

// ---------- Third-party notices (HYG-3) ----------
// AboutLibraries collects every dependency's licence from its POM chain; RenderThirdPartyNotices (buildSrc)
// renders the list to assets/third_party_notices.txt for each variant, so the APK carries its own notices,
// which the About screen shows. The lock app has no third-party runtime dependencies, so it has none.
// The build fails when a licence isn't allowed below, a library names none, or a runtime module is missing.

/**
 * Licences the parent app may ship, as AboutLibraries identifies them. Add one only after reading it. ASDKL,
 * the Android Software Development Kit License, covers the Google Play services and Firebase modules that push
 * delivery and the map need; it is not open source, but it allows shipping them in an app and they are listed
 * like the others.
 */
val shippableLicenses = listOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ASDKL")

/**
 * Licences allowed only for the modules named, by AboutLibraries' licence name (lowercase) to module ids or
 * groups, so a new dependency under the same terms still fails the build until someone reads them.
 * Read on 2026-11-19.
 * - libyuv's licence, which CameraX's core module bundles for image conversion. It is BSD-3-Clause; its POM
 *   only links to it, so its full text is supplied in notices/licenses/libyuv-bsd.json.
 */
val licensesForModules = mapOf("bsd license" to listOf("androidx.camera:camera-core"))

aboutLibraries {
    // Licence text comes from the POMs, AboutLibraries' bundled SPDX texts and notices/licenses/*.json (texts the
    // POMs only link to, keyed by AboutLibraries' licence hash); the build never fetches anything from GitHub.
    collect {
        configPath.set(file("notices"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
    }
    license {
        strictMode.set(StrictMode.FAIL)
        allowedLicenses.addAll(shippableLicenses)
        allowedLicensesMap.putAll(licensesForModules)
    }
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        // One export file per variant; by default every variant writes the same file.
        aboutLibraries.exports {
            register(variant.name) {
                this.variant.set(variant.name)
                outputFile.set(layout.buildDirectory.file("generated/aboutLibraries/${variant.name}/aboutlibraries.json"))
            }
        }
        val render = tasks.register<RenderThirdPartyNotices>("render${suffix}ThirdPartyNotices") {
            definitions.from(tasks.named("exportLibraryDefinitions$suffix"))
            runtimeModules.set(
                provider {
                    variant.runtimeConfiguration.incoming.resolutionResult.allComponents
                        .map { it.id }
                        .filterIsInstance<ModuleComponentIdentifier>()
                        .map { "${it.group}:${it.module}" }
                        .toSet()
                },
            )
            outputDir.set(layout.buildDirectory.dir("generated/thirdPartyNotices/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(render, RenderThirdPartyNotices::outputDir)
    }
}
