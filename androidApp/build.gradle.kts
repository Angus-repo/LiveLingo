plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}
val releaseStore = providers.environmentVariable("LIVELINGO_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("LIVELINGO_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LIVELINGO_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LIVELINGO_KEY_PASSWORD").orNull
android {
    namespace = "app.livelingo.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.livelingo.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.10"
    }
    buildFeatures { compose = true }
    if (!releaseStore.isNullOrBlank()) {
        require(!releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            "Release signing requires all LIVELINGO_KEYSTORE_* / LIVELINGO_KEY_* environment variables."
        }
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
}
