plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":magic-agent:browser:api"))
            implementation(project(":core:storage:api"))
            implementation(project(":core:logging"))
            implementation(project(":magic-chat:api"))
            implementation(project(":magic-common:research"))
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        jvmMain.dependencies {
            implementation(libs.playwright)
            implementation(libs.html.validator)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":magic-common:tools:impl"))
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("magicpaper.browser.native", providers.gradleProperty("magicpaper.browser.native").getOrElse("false"))
}

tasks.register<JavaExec>("installAgentBrowser") {
    group = "verification"
    description = "Install managed Chromium for the browser owner and its native tests."
    classpath = configurations.getByName("jvmRuntimeClasspath")
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}
