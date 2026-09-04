import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "io.aequicor.magicpaper.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MagicPaper"
            packageVersion = "1.0.0"
            // Иконки пакетов генерирует: python3 assets/icon/gen_icons.py
            macOS {
                iconFile.set(project.file("../assets/icon/dist/magicpaper.icns"))
            }
            windows {
                iconFile.set(project.file("../assets/icon/dist/magicpaper.ico"))
                menu = true
                menuGroup = "MagicPaper"
            }
            linux {
                iconFile.set(project.file("../assets/icon/dist/magicpaper_512.png"))
            }
        }
    }
}