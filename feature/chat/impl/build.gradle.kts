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
            implementation(project(":designSystem"))
            implementation(project(":core:platform"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:api"))
            implementation(libs.koin.core)
            implementation(project(":feature:chat:api"))
            implementation(project(":feature:coding:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
        }
        commonTest.dependencies {
            implementation(project(":core:storage:api"))
            implementation(project(":core:ai:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
            implementation(project(":feature:settings:impl"))
            implementation(project(":feature:coding:impl"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.markdownRenderer.m3)
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.paperEditor.it", providers.gradleProperty("magicpaper.paperEditor.it").getOrElse("false"))
    val launcher = when {
        System.getProperty("os.name").startsWith("Mac") -> "PaperEditor.app/Contents/MacOS/PaperEditor"
        System.getProperty("os.name").startsWith("Windows") -> "PaperEditor/PaperEditor.exe"
        else -> "PaperEditor/bin/PaperEditor"
    }
    systemProperty("magicpaper.paperEditor.testExecutable", providers.gradleProperty("magicpaper.paperEditor.testExecutable")
        .getOrElse(rootProject.file("tools/paper-editor/build/compose/binaries/main/app/$launcher").absolutePath))
    systemProperty("magicpaper.pi.it", providers.gradleProperty("magicpaper.pi.it").getOrElse("false"))
    systemProperty("magicpaper.codex.it", providers.gradleProperty("magicpaper.codex.it").getOrElse("false"))
    systemProperty("magicpaper.research.native", providers.gradleProperty("magicpaper.research.native").getOrElse("false"))
}
