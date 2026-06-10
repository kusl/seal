// The buildscript block must come first in a Kotlin-DSL build script.
//
// Why it exists at all: AGP 9's built-in Kotlin has a *runtime* dependency on
// the Kotlin Gradle plugin and bundles KGP 2.2.10. Google's documented way to
// compile with a NEWER Kotlin (we want 2.4.0, the latest stable) is to put the
// kotlin-gradle-plugin — and, to keep the processor toolchain consistent, the
// KSP plugin — on the build classpath explicitly; Gradle's highest-version
// conflict resolution then makes built-in Kotlin use them.
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
//   ("Runtime dependency on Kotlin Gradle plugin" → "Upgrade to a higher KGP version")
//
// ⚠ Version catalogs are not reliably accessible inside `buildscript {}`, so
// these two literals are intentionally hardcoded. Keep them in lockstep with
// the `kotlin` and `ksp` entries in gradle/libs.versions.toml.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // org.jetbrains.kotlin.android is GONE: AGP 9 ships built-in Kotlin and the
    // standalone plugin is incompatible with the new DSL. The compiler-plugin
    // subplugins below (serialization, Compose) still apply per-module and
    // attach to the compilation that built-in Kotlin runs.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.room) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
