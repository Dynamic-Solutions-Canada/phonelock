// Build logic shared by the app modules: the task that renders the parent app's third-party notices. It uses
// only Gradle's own API, so it needs no plugin dependencies.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
