pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    // Resolves and downloads the JDK requested by `kotlin { jvmToolchain(21) }` when the build
    // host doesn't already have it. 1.0.0 is the current stable release (was 0.4.0).
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
rootProject.name = "Seal"
include(":app")
include(":color")
