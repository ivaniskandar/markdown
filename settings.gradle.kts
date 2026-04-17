rootProject.name = "Markdown"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/ivaniskandar/latex")
            credentials {
                username = providers.gradleProperty("githubUser").getOrNull()
                    ?: providers.environmentVariable("GITHUB_USER").getOrNull()
                password = providers.gradleProperty("githubApiKey").getOrNull()
                    ?: providers.environmentVariable("GITHUB_API_KEY").getOrNull()
            }
            mavenContent {
                includeModuleByRegex("xyz.ivaniskandar", "latex-.*")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":markdown-parser")
include(":markdown-renderer")
include(":markdown-preview")
include(":markdown-benchmark")
include(":composeApp")
include(":androidapp")
