plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}
kotlin {
    jvmToolchain(17)
    jvm()
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
