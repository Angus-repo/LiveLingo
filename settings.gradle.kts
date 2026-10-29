pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx\\..*")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version "2.2.21"
        kotlin("android") version "2.2.21"
        kotlin("jvm") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
        kotlin("plugin.compose") version "2.2.21"
        id("org.jetbrains.compose") version "1.9.3"
        id("com.android.application") version "8.11.1"
        id("com.android.library") version "8.11.1"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx\\..*")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "LiveLingo"
include(":core")
if (providers.gradleProperty("headless").orNull == "true") {
    project(":core").buildFileName = "build-headless.gradle.kts"
} else {
    include(":shared", ":androidApp")
}
