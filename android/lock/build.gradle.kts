import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Keystore passwords live with the keystore in .secrets/ (git-ignored), not in this public file.
val signing = Properties()
val signingFile = rootProject.file("../.secrets/signing.properties")
if (signingFile.exists()) signingFile.inputStream().use { signing.load(it) }

val gitVersionCode = rootProject.extra["gitVersionCode"] as Int

android {
    namespace = "home.phonelock"
    compileSdk = 37

    // Ship only the app's languages (LOC-10): the nine in res/xml/locales_config.xml, plus the
    // qualifiers libraries use for the same languages (zh-rCN, pt-rBR), so their built-in text matches.
    androidResources {
        localeFilters += listOf("en", "en-rCA", "es", "fr", "fr-rCA", "de", "b+zh+Hans", "zh-rCN", "pt", "pt-rBR", "ja")
    }

    defaultConfig {
        applicationId = "home.phonelock"
        minSdk = 33
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = "1.0.$gitVersionCode"
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
        // Our own key, kept with the project so future updates install over the top.
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
        // Signed with the debug key, a debug lock app cannot update over a release one, or take its updates.
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The lock app is plain Java. AGP 9 compiles Kotlin in every module and adds the Kotlin standard library
// (2 MB), which this app never uses, so leave it out: smaller downloads for setup and self-updates.
// Only the app's own classpaths: build tools such as lint still need it.
configurations.matching { it.name.endsWith("RuntimeClasspath") || it.name.endsWith("CompileClasspath") }
    .configureEach { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }

dependencies {
    // Tests only, never shipped.
    testImplementation(libs.junit)
}
