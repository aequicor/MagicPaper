plugins { id("magicpaper.compose-library") }

kotlin {
    sourceSets {
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/rendering")) }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/filesystems")) }
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
            implementation(project(":feature:transcript"))
            implementation(project(":feature:tools:api"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:docs:api"))
            implementation(project(":feature:plugins:api"))
            implementation(project(":feature:skills:api"))
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(project(":core:storage:api"))
            implementation(project(":core:storage:impl"))
            implementation(project(":core:ai:impl"))
            implementation(project(":feature:settings:impl"))
            implementation(project(":feature:skills:impl"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(libs.ktor.clientCio)
            implementation(libs.playwright)
            implementation(libs.html.validator)
        }
        jvmTest.dependencies {
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
}
