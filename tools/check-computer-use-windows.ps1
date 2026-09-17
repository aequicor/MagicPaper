# Run in an unlocked, interactive Windows desktop. Uses isolated fixture windows only.
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This check requires Windows; a successful macOS build is not Windows acceptance.'
}
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    & .\gradlew.bat :feature:session:impl:jvmTest --tests '*WindowCaptureExclusionTest' --tests '*DesktopComputerWindowsIntegrationTest' --tests '*DesktopComputerUseTest' --tests '*ScreenshotEncodingTest' '-Pmagicpaper.computer.windows.native=true' --rerun
    if ($LASTEXITCODE -ne 0) { throw 'Computer capture/input checks failed.' }
    [xml]$nativeResult = Get-Content -Raw 'feature/session/impl/build/test-results/jvmTest/TEST-io.aequicor.magicpaper.data.computer.DesktopComputerWindowsIntegrationTest.xml'
    if ([int]$nativeResult.testsuite.tests -ne 1 -or [int]$nativeResult.testsuite.skipped -ne 0) {
        throw 'The native Windows test did not run.'
    }
    & .\gradlew.bat :desktopApp:test --tests '*DesktopComputerWindowTest' '-Pmagicpaper.window.native=true' '-Pmagicpaper.computer.input.native=true' --rerun
    if ($LASTEXITCODE -ne 0) { throw 'Compact window/overlay checks failed.' }
    [xml]$windowResult = Get-Content -Raw 'desktopApp/build/test-results/test/TEST-io.aequicor.magicpaper.DesktopComputerWindowTest.xml'
    if ([int]$windowResult.testsuite.tests -ne 2 -or [int]$windowResult.testsuite.skipped -ne 0) {
        throw 'The native window tests did not run.'
    }
    & .\gradlew.bat :designSystem:jvmTest --tests '*PaperComputerFeedbackTest' --rerun
    if ($LASTEXITCODE -ne 0) { throw 'Stop control/render checks failed.' }
    Write-Host 'PASS: native Windows capture, pointer, window restoration and Stop control checks.'
    Write-Host 'Evidence: feature/session/impl/build/reports/computer-use/windows-native.txt and module JUnit reports.'
} finally {
    Pop-Location
}
