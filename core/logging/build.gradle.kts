plugins { id("magicpaper.kmp-library") }

kotlin {
    sourceSets {
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
