plugins { id("magicpaper.jvm-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":backend-agents:api"))
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest { kotlin.srcDir(rootProject.file("testSupport/nativeLifecycle")) }
        jvmTest.dependencies { implementation(project(":backend-agents:lifecycle:impl")) }
    }
}
