; Установщик Portal Launcher (Inno Setup). Папку установки выбирает пользователь (любой диск).
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6F3C2D0E-8B7A-4A53-9D0E-5B1D7A2C9E22}
AppName=Portal Launcher
AppVersion={#AppVersion}
AppPublisher=Portal
DefaultDirName={userpf}\Portal Launcher
DefaultGroupName=Portal
DisableDirPage=no
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\build\setup
OutputBaseFilename=PortalLauncher-Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\PortalLauncher.exe
Compression=lzma2/normal
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
ShowLanguageDialog=auto

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\build\compose\binaries\main\app\PortalLauncher\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Portal Launcher"; Filename: "{app}\PortalLauncher.exe"
Name: "{autodesktop}\Portal Launcher"; Filename: "{app}\PortalLauncher.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\PortalLauncher.exe"; Description: "{cm:LaunchProgram,Portal Launcher}"; Flags: nowait postinstall skipifsilent
