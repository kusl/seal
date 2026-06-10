@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import io.sentry.android.gradle.extensions.InstrumentationFeature
import io.sentry.android.gradle.instrumentation.logcat.LogcatLevel
import java.io.FileInputStream
import java.util.EnumSet
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.room)
    alias(libs.plugins.ktfmt.gradle)
    // Sentry Android Gradle plugin: handles (optional) R8 mapping upload and the bytecode
    // auto-instrumentation we configure in the `sentry { }` block below. Applied only here in
    // `:app` (mirroring how ktfmt is applied), so no change to the root build.gradle.kts is needed.
    alias(libs.plugins.sentry)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")

val splitApks = !project.hasProperty("noSplits")

val abiFilterList = (properties["ABI_FILTERS"] as String).split(';')

// 64-bit only as of the Android-15+ baseline (minSdk 35): every Android 15 device is 64-bit, and
// MMKV 2.x ships no 32-bit native libraries. armeabi-v7a / x86 are therefore gone from the splits
// below. The numeric codes for the SURVIVING ABIs are unchanged (arm64-v8a=2, x86_64=4) so the
// per-ABI versionCode offsets stay identical and Obtainium updates keep working.
val abiCodes = mapOf("arm64-v8a" to 2, "x86_64" to 4)

// ── Version resolution ────────────────────────────────────────────────────────
//
// CI passes -PversionNameOverride=... and -PversionCodeOverride=... to inject
// a timestamp-based auto-bumping version. When building locally (or if the
// properties aren't set), we fall back to the values from buildSrc/Version.kt.
val baseVersionName: String = if (project.hasProperty("versionNameOverride")) {
    project.property("versionNameOverride") as String
} else {
    currentVersion.name
}

val currentVersionCode: Int = if (project.hasProperty("versionCodeOverride")) {
    (project.property("versionCodeOverride") as String).toInt()
} else {
    currentVersion.code.toInt()
}

android {
    compileSdk = 36

    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
        signingConfigs {
            create("githubPublish") {
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
                storeFile = file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"].toString()
            }
        }
    }

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.junkfood.seal"
        // Android 15+ only, per the project's stated support policy. This makes every
        // `SDK_INT >= 26/30/33` branch in the codebase constant-true (lint flags them as
        // ObsoleteSdkInt; they are removed opportunistically in files touched by this round).
        minSdk = 35
        targetSdk = 36
        versionCode = currentVersionCode

        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Sentry DSN, exposed to the app as BuildConfig.SENTRY_DSN. A DSN is a *public* client-side
        // identifier (it only permits sending events, never reading them), so it is safe to commit.
        // App.kt only initializes Sentry when this is non-blank; the `fdroid` flavor overrides it to
        // "" below so F-Droid builds ship with zero telemetry.
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"https://765791294bb0c81b06d4784a8913ba1c@o4511444968079360.ingest.de.sentry.io/4511529508995152\"",
        )

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    // 64-bit only — see the abiCodes note above.
                    include("arm64-v8a", "x86_64")
                    isUniversalApk = true
                }
            }
        } else {
            ndk { abiFilters.addAll(abiFilterList) }
        }
    }

    room { schemaDirectory("$projectDir/schemas") }

    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val name =
                    if (splitApks) {
                        output.filters
                            .find { it.filterType == FilterConfiguration.FilterType.ABI }
                            ?.identifier
                    } else {
                        abiFilterList.firstOrNull()
                    }

                val baseAbiCode = abiCodes[name]

                if (baseAbiCode != null) {
                    output.versionCode.set(baseAbiCode + (output.versionCode.get() ?: 0))
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
        }
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Seal Debug")
        }
    }

    flavorDimensions += "publishChannel"

    productFlavors {
        create("generic") {
            dimension = "publishChannel"
            isDefault = true
        }

        create("githubPreview") {
            dimension = "publishChannel"
            applicationIdSuffix = ".preview"
            resValue("string", "app_name", "Seal Preview")
        }

        create("fdroid") {
            dimension = "publishChannel"
            versionName = "$baseVersionName-(F-Droid)"
            // F-Droid forbids non-free network services / tracking. Blank out the DSN so Sentry is
            // never initialized in this flavor (App.kt no-ops on a blank DSN), and `ignoredFlavors`
            // in the `sentry { }` block disables the plugin's instrumentation/upload for it too.
            buildConfigField("String", "SENTRY_DSN", "\"\"")
        }
    }

    lint { disable.addAll(listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")) }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "Seal-${defaultConfig.versionName}-${name}.apk"
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs.useLegacyPackaging = true
    }
    androidResources { generateLocaleConfig = true }

    namespace = "com.junkfood.seal"
}

// ── Sentry Gradle plugin configuration ────────────────────────────────────────
//
// Read the upload auth token from the environment via the Provider API (rather than
// System.getenv) so the whole block stays compatible with the configuration cache
// (org.gradle.configuration-cache=true is enabled for this project).
val sentryAuthToken = providers.environmentVariable("SENTRY_AUTH_TOKEN")

sentry {
    org.set("collabs-with-kushal")
    projectName.set("seal")
    authToken.set(sentryAuthToken)

    // Generate the R8 mapping UUID and embed it into the build, but only *upload* the mapping when
    // a SENTRY_AUTH_TOKEN is present (i.e. in CI). Local/dry builds therefore never fail for lack
    // of a token. (This project uses `-dontobfuscate`, so symbol names are intact even without the
    // mapping; uploading it additionally de-inlines/maps line numbers for the cleanest traces.)
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryAuthToken.map { it.isNotBlank() }.orElse(false))

    // We declare every io.sentry:* artifact explicitly in the version catalog (sentry-android and,
    // now, sentry-okhttp), so the plugin's auto-installation stays off — versions are pinned by us,
    // in one place, on one shared `sentry` version.
    autoInstallation { enabled.set(false) }

    // Don't instrument or upload anything for the F-Droid flavor.
    ignoredFlavors.set(listOf("fdroid"))

    // Bytecode auto-instrumentation. This is how we "log everything" without touching any of the
    // app's source files:
    //   • DATABASE + FILE_IO  → spans for Room/SQLite and java.io file operations. Besides timing,
    //                            these power Sentry's server-side "DB/File-I/O on the main thread"
    //                            ANR root-cause detection.
    //   • OKHTTP              → spans + breadcrumbs for every OkHttp call (update checks, sponsor
    //                            list). Enabled now that okhttp is on a stable 5.x release; backed
    //                            by the io.sentry:sentry-okhttp artifact added in dependencies.
    //   • logcat (VERBOSE)    → turns every android.util.Log.* call into a Sentry breadcrumb, so the
    //                            app's existing logging shows up on the timeline of every event.
    //
    // Intentionally NOT enabled:
    //   • COMPOSE — would add navigation breadcrumbs but requires the sentry-compose-android
    //               artifact and bytecode-instruments NavControllers. Left off to keep the change
    //               minimal/low-risk; add the dependency + feature later if route breadcrumbs help.
    tracingInstrumentation {
        enabled.set(true)
        features.set(
            EnumSet.of(
                InstrumentationFeature.DATABASE,
                InstrumentationFeature.FILE_IO,
                InstrumentationFeature.OKHTTP,
            )
        )
        logcat {
            enabled.set(true)
            minLevel.set(LogcatLevel.VERBOSE)
        }
    }

    // Don't send the plugin's own build-time telemetry to Sentry.
    telemetry.set(false)
}

ktfmt { kotlinLangStyle() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":color"))

    implementation(libs.bundles.core)

    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidxCompose)
    implementation(libs.bundles.accompanist)

    // Coil 3: compose bindings + the OkHttp-backed network fetcher. In Coil 3 the http(s) fetcher
    // moved out of the core artifact; without coil-network-okhttp every remote thumbnail would
    // silently fail to load. The fetcher self-registers via ServiceLoader — no code required.
    implementation(libs.coil.kt.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)

    implementation(libs.bundles.youtubedlAndroid)

    implementation(libs.mmkv)

    // Sentry: crash + ANR + performance reporting. `sentry-android` is the umbrella artifact
    // (core + NDK native-crash handler); `sentry-okhttp` backs the OKHTTP instrumentation feature
    // enabled in the sentry { } block above. Both ride the same `sentry` catalog version — keep it
    // that way (or switch to io.sentry:sentry-bom) to avoid the SDK's deliberate "mixed versions"
    // init crash.
    implementation(libs.sentry.android)
    implementation(libs.sentry.okhttp)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.compose.ui.tooling)
}

// ── CI helper task ────────────────────────────────────────────────────────────
//
// Prints the app's version name to stdout so the GitHub Actions workflow can
// capture it without parsing Kotlin source files.
//
// Usage:  ./gradlew printVersionName
// Output: 2.0.0-alpha.5   (whatever currentVersion.name resolves to)
//
// NOTE: This always prints the Version.kt value, NOT the CI override.
// The CI uses this as the "base" and then appends a timestamp.
tasks.register("printVersionName") {
    group = "versioning"
    description = "Prints the current versionName to stdout for CI consumption."
    doLast {
        println(currentVersion.name)
    }
}
