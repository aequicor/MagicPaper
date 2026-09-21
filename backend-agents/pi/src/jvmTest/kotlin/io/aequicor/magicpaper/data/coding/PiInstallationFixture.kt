package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.backend.NativeResources
import java.io.File

internal fun testInstallation(root: File) = PiNativeInstallation(root, NativeResources { null },
    NativeDiagnostics { _, event, failure, _ -> throw AssertionError("Unexpected native diagnostic: $event", failure) })
