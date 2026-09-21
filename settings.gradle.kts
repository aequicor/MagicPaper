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
include(":core:state-machine:api")
include(":core:state-machine:impl")
include(":magic-common:research")
include(":magic-common:prompts")
include(":magic-common:questionnaire:api")
include(":magic-common:questionnaire:impl")
include(":magic-common:tools:api")
include(":magic-common:tools:impl")
include(":magic-common:navigation:api")
include(":magic-common:transcript")
include(":magic-common:media:api", ":magic-common:media:impl")
include(":magic-common:request-pins:api", ":magic-common:request-pins:impl")
include(":magic-chat:api")
include(":magic-agent:runtime:impl")
include(":magic-agent:computer:api", ":magic-agent:computer:impl")
include(":magic-agent:organism:api", ":magic-agent:organism:impl")
include(":magic-agent:planning:api", ":magic-agent:planning:impl")
include(":magic-agent:browser:api", ":magic-agent:browser:impl")
include(":backend-agents:api", ":backend-agents:factory", ":backend-agents:pi", ":backend-agents:codex", ":backend-agents:claude")
include(":magic-agent:native-recovery:api")
include(":magic-agent:runtime:api")
include(":magic-chat:impl")
include(":feature:settings:api")
include(":feature:settings:impl")
include(":feature:docs:api")
include(":feature:docs:impl")
include(":feature:plugins:api")
include(":feature:plugins:impl")
include(":feature:skills:api")
include(":feature:skills:impl")

// Development tool only: ordinary application builds never configure the external build.
if (providers.gradleProperty("paperEditor").orNull == "true") {
    check(file("tools/mission-visualization/settings.gradle.kts").isFile) {
        "Paper editor requires: git submodule update --init --recursive"
    }
    includeBuild("tools/mission-visualization") {
        dependencySubstitution {
            substitute(module("io.aequicor.visualization:editor")).using(project(":shared"))
            substitute(module("io.aequicor.visualization:backend-compose")).using(project(":engine:backend-compose"))
        }
    }
    include(":tools:paper-plugin", ":tools:paper-editor")
}

include(":backend-agents:lifecycle:impl")

include(":magic-agent:workspace:api", ":magic-agent:workspace:impl")

include(":magic-agent:checks:api", ":magic-agent:checks:impl")
