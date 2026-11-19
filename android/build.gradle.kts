plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Formatting check and rewrite (approved: Spotless, STYLE-8, D-092). Build tooling, never shipped.
    alias(libs.plugins.spotless)
    // Third-party notices for the parent app (approved: standards session for Ryan, HYG-3 (2026-11-19)).
    // Build tooling only: the plugin collects licences at build time and no runtime library is shipped.
    alias(libs.plugins.aboutlibraries) apply false
}

// One formatter for the Kotlin, Java and Gradle sources (STYLE-8, D-092): spotlessApply rewrites files,
// spotlessCheck fails on any difference. Kotlin and the Gradle scripts use ktlint with the same style as
// .editorconfig; Java uses palantir-java-format, whose four-space indent and 120-column lines match the
// existing Java.
val ktlintRules = mapOf(
    "ktlint_code_style" to "intellij_idea",
    // Compose functions are named like classes, so the naming rule skips them.
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
)
spotless {
    kotlin {
        target("companion/src/**/*.kt", "buildSrc/src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
    java {
        // Only files inside this Gradle project can be formatted; scripts/dev/ParentCli.java, outside it, is
        // kept in the same style by hand.
        target("lock/src/**/*.java")
        palantirJavaFormat(libs.versions.palantirJavaFormat.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
}

// One version scale for both apps, local builds and releases: the number of commits on this branch.
// Updates (the lock app's self-update, Play for the parent app) need it to grow with every release.
// Never rewrite or squash pushed history: a lower count would block all future updates (no downgrades).
extra["gitVersionCode"] = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
