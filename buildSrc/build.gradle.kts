plugins { `kotlin-dsl` }

repositories {
    mavenCentral()
    google()
}

// The previous explicit `gradleApi()` and `localGroovy()` dependencies are gone:
// the kotlin-dsl plugin already puts the Gradle API on the compile classpath,
// and nothing in buildSrc is written in Groovy. Version.kt needs no
// dependencies at all.

kotlin { jvmToolchain(21) }
