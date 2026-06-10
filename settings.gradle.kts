pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    // Resolves and downloads the JDK requested by the `java { toolchain }` blocks in :app/:color
    // (and buildSrc's jvmToolchain) when the build host doesn't already have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // mavenLocal() is gone: it made resolution depend on whatever happened to be in a
        // developer's ~/.m2, and this project builds exclusively on clean CI runners where
        // it is always empty. Removing it makes every build hermetic and reproducible.
    }
}
rootProject.name = "Seal"
include(":app")
include(":color")
