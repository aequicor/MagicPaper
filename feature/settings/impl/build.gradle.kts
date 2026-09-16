plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/rendering")) }
        commonMain.dependencies {
            implementation(project(":core:logging"))
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
            api(project(":core:model"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.decompose)
            implementation(libs.compose.foundation)
            implementation(libs.compose.uiToolingPreview)
            implementation(project(":designSystem"))
            implementation(project(":core:platform"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(libs.koin.core)
            implementation(project(":feature:session:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
            implementation(project(":feature:session:impl"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
}
