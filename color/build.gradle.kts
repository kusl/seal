plugins {
    alias(libs.plugins.android.library)
    // No org.jetbrains.kotlin.android here either — AGP 9's built-in Kotlin
    // compiles this module's sources. The Compose compiler subplugin still
    // applies (Monet.kt and friends are @Composable) and attaches to the
    // built-in compilation.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.junkfood.seal.color"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Matches :app — the library is never consumed anywhere else. (AGP 9
        // also *requires* consumers to compile with the same-or-higher
        // compileSdk than their libraries, which :app satisfies at 37.)
        minSdk {
            version = release(34)
        }
    }

    // Single source of truth for the JVM bytecode level, shared with :app.
    // Built-in Kotlin derives its jvmTarget from targetCompatibility, so this
    // block pins javac AND kotlinc to 21 — replacing the old
    // `kotlin { jvmToolchain(21) }` from the removed kotlin-android plugin.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            isMinifyEnabled = true
        }
    }
}

// Resolve a real JDK 21 for compilation (foojay convention in settings.gradle.kts
// downloads one if the build host lacks it) — same pattern as :app.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.core.ktx)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
}
