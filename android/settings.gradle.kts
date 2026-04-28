pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Foojay resolver lets Gradle auto-download a JDK 21 (matching `kotlin { jvmToolchain(21) }` in
// :app) when one isn't already installed. Without this, fresh checkouts on machines that only
// have JDK 25 (or whatever) would fail with "No matching toolchains found".
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Vela"
include(":app")
