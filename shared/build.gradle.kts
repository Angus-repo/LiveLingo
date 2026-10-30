plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}
kotlin {
    jvmToolchain(17)
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("io.ktor:ktor-client-core:3.3.1")
            implementation("io.ktor:ktor-client-websockets:3.3.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.core:core-ktx:1.16.0")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
            implementation("io.ktor:ktor-client-okhttp:3.3.1")
        }
        androidUnitTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.1.0")
            implementation("com.squareup.okhttp3:okhttp-tls:5.1.0")
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.1") }
    }
}
android {
    namespace = "app.livelingo.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
