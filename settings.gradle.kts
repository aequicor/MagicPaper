rootProject.name = "MagicPaper"

pluginManagement {
    includeBuild("build-logic")
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

include(":androidApp")
include(":desktopApp")
include(":app")
include(":designSystem")
include(":webApp")

// Feature implementations are assembled only by :app.
include(":core:model")
include(":core:logging")
include(":core:platform")
include(":core:ai:api")
include(":core:ai:impl")
include(":core:storage:api")
include(":core:storage:impl")
include(":feature:chat:api")
include(":feature:chat:impl")
include(":feature:coding:api")
include(":feature:coding:impl")
include(":feature:settings:api")
include(":feature:settings:impl")
include(":feature:docs:api")
include(":feature:docs:impl")
include(":feature:plugins:api")
include(":feature:plugins:impl")
include(":feature:skills:api")
include(":feature:skills:impl")
