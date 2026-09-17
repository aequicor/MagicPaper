; Inno Setup script for MagicPaper (Windows).
;
; Wraps the Compose app-image produced by
;   :desktopApp:createReleaseDistributable
; into a per-user installer. The former jpackage MSI was dropped: it installed
; per-machine (elevation required) and registered magicpaper:// under HKLM, while
; Inno Setup installs into user locations without administrator rights and keeps
; the protocol handler per-user, plus a final "Launch" checkbox and an opt-in
; desktop icon that jpackage cannot express.
;
; Overridable defines (passed by :desktopApp:packageReleaseInnoSetup via ISCC /D...);
; the defaults let a dev compile locally straight after createReleaseDistributable:
;   AppVersion - product version, e.g. 1.0.0
;   AppDir     - path to the built app-image folder (contains MagicPaper.exe)
;   SetupIcon  - path to the .ico used for the installer wizard

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif
#ifndef AppDir
  #define AppDir "..\..\build\compose\binaries\main-release\app\MagicPaper"
#endif
#ifndef SetupIcon
  #define SetupIcon "..\..\..\assets\icon\dist\magicpaper.ico"
#endif

#define AppName "MagicPaper"
#define AppExe "MagicPaper.exe"
#define AppPublisher "Aequicor"

[Setup]
; Stable AppId so future versions upgrade in place instead of installing twice.
AppId={{8C4A2E61-5D7F-4B39-9E26-1A7C0D8F4B53}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
; per-user install => no administrator rights; {auto*} resolve to user locations.
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#AppExe}
SetupIconFile={#SetupIcon}
OutputDir=Output
OutputBaseFilename={#AppName}-{#AppVersion}-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

; Register magicpaper:// as a per-user URL protocol (parity with the former MSI
; handler, but under HKCU so no elevation is involved). The activation broker of a
; running instance still receives the link; uninstall removes the whole key.
[Registry]
Root: HKCU; Subkey: "Software\Classes\magicpaper"; ValueType: string; ValueName: ""; ValueData: "URL:MagicPaper Protocol"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\magicpaper"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKCU; Subkey: "Software\Classes\magicpaper\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\{#AppExe},0"
Root: HKCU; Subkey: "Software\Classes\magicpaper\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""
