plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Single source of truth for the JVM target. The old file *also* declared Java 1.8 via a top-level
// `java {}` block and a (duplicated) `compileOptions {}` — leaving javac on 1.8 while Kotlin
// targeted 21, a latent jvm-target mismatch that newer AGP/KGP versions reject. Both blocks are
// gone; the toolchain below now configures javac and kotlinc consistently.
kotlin {
    jvmToolchain(21)
}
android {
    compileSdk = 36
    defaultConfig {
        // Matches :app — the library is never consumed anywhere else.
        minSdk = 35
    }
    namespace = "com.junkfood.seal.color"
    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            isMinifyEnabled = true
        }
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
