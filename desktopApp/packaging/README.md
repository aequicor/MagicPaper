# Native URL activation

Build installers on their target operating system with `:desktopApp:packageDmg`,
`:desktopApp:packageReleaseInnoSetup` (Windows), or `:desktopApp:packageDeb`. The
current-OS aggregate task and release variants use the same integration. JBR 21
remains the bundled runtime.

On Windows, `:desktopApp:packageReleaseInnoSetup` compiles
`packaging/windows/MagicPaper.iss` with the Inno Setup 6 compiler (`ISCC.exe`
resolved from `INNO_SETUP_PATH`, the standard install directories, or `PATH`) into
`build/innosetup/MagicPaper-<version>-setup.exe`. The setup installs per-user
(`PrivilegesRequired=lowest`), so no administrator rights are required, registers
`magicpaper://` under `HKCU\Software\Classes\magicpaper` and removes the key on
uninstall. The retired jpackage MSI was per-machine, required elevation and a
manually provisioned WiX toolchain. `:desktopApp:packageReleasePortableZip`
archives the same release app-image into
`build/portable/MagicPaper-<version>-portable-windows-x64.zip` without an installer:
unpacking and running it requires no administrator rights and registers nothing.
Both keep data in the user-owned `~/.MagicPaper` directory.

macOS declares `magicpaper` in `CFBundleURLTypes`. Linux packaging supplies a desktop
entry with `%u` and `x-scheme-handler/magicpaper`; jpackage installs/removes it
through its standard `xdg-desktop-menu` package hooks. Linux requires the normal
jpackage Debian packaging prerequisites.

Compose's packaging task clears its own resource directory during execution. The
MSI/DEB tasks therefore delegate to protocol-aware jpackage tasks that consume the
same Compose application image and a separate resource directory. The WiX override
is derived from the installed JBR's template and fails if its expected structure
changes. No generated JDK template is checked in.

The process acquires a file lock in its own activation namespace
(`~/.MagicPaper/activation/instance-<hash>`), derived from the directory that holds the
running application code: the `app` directory of a jpackage image (installed package,
`runDistributable`, portable copy) or the classes directory of an unpackaged Gradle/IDE
launch. Launches of the same build share one owner: a second one sends its bounded
activation request over authenticated loopback IPC and exits, so the `magicpaper://`
handler always reaches the window that is already open. A launch of a different build —
for example `main()` from the IDE while the installed application runs — takes its own
namespace instead of forwarding to that foreign runtime and exiting without a window.
macOS OpenURI events enter the same activation queue. Queued links
are processed after startup and the welcome gate. The existing window is restored
and focused on activation. Runtime locks are released by the OS after a crash;
stale endpoint metadata is replaced only by the next lock owner.

Verify an installed package on every supported operating system:

1. Open `magicpaper://docs` with the application closed: `open` on macOS,
   `Start-Process` in PowerShell, or `xdg-open` on Linux.
2. Minimize the window, open `magicpaper://settings/models`, and verify the same
   process/window restores and follows the route.
3. Open a link during welcome and finish welcome; verify that route opens.
4. Visit A → B → A, go Back, quit, reopen, and verify both Back and Forward.
5. Restart after force-ending the process and confirm a stale endpoint does not
   prevent startup. Uninstall and verify the package-owned protocol handler is gone.
6. With the installed application running, launch the same installation again and verify
   it exits after raising the existing window; then launch a build from another location
   and verify it opens its own window instead of forwarding to the installed runtime.

An unpackaged Gradle launch accepts URI arguments but does not install an OS URL
handler. Protocol registration is tested using the installed package. Instances of
different builds share the `~/.MagicPaper` data directory; the exclusive locks of the
skill repository, experience journal and planning workspaces report contention
explicitly.
