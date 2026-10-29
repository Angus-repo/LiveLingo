plugins {
    base
    // Shared classloaders are required by Kotlin/Native build services on macOS.
    kotlin("multiplatform") apply false
    kotlin("android") apply false
    kotlin("plugin.serialization") apply false
    kotlin("plugin.compose") apply false
    id("org.jetbrains.compose") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
}
allprojects { group = "app.livelingo"; version = "0.1.1" }
