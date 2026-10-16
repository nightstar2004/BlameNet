; BlameNet Windows 安装脚本（Inno Setup）
; 由 package-exe.bat 调用：
;   ISCC.exe /DAppVersion=<纯数字版本> /DFullVersion=<完整版本> installer.iss

#ifndef AppVersion
  #define AppVersion "0.1"
#endif

#ifndef FullVersion
  #define FullVersion "0.1"
#endif

; 目录名 / 启动器名 / 安装包名 使用完整版本号（如 0.1-alpha）
#define AppExeName "BlameNet-" + FullVersion

[Setup]
AppId={{5B2C1D3E-8F7A-4C6B-9D0E-1F2A3B4C5D6E}
AppName=BlameNet
; Windows 版本资源只接受纯数字，故 AppVersion 用纯数字版本
AppVersion={#AppVersion}
AppPublisher=BlameNet
AppPublisherURL=
AppSupportURL=
AppUpdatesURL=
DefaultDirName={autopf}\{#AppExeName}
; 关键：用户点“浏览”选择目录后，自动把 BlameNet-<版本号> 追加为最后一段子目录
AppendDefaultDirName=yes
; 按当前用户安装（无需管理员权限），结果目录默认可写
PrivilegesRequired=lowest
DisableDirPage=no
DisableProgramGroupPage=yes
OutputBaseFilename=BlameNet-Setup-{#FullVersion}
OutputDir=target\exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#AppExeName}.exe
UninstallDisplayName=BlameNet {#FullVersion}

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: unchecked

[Files]
Source: "target\app-image\{#AppExeName}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
; 使用指南（只讲使用方法，随程序安装到根目录）
Source: "使用指南.txt"; DestDir: "{app}"; Flags: ignoreversion
; 原生文件夹选择器（安装过程中临时使用，不安装到目标目录）
Source: "build-tools\FolderPicker\out\FolderPicker.exe"; DestDir: "{tmp}"; Flags: dontcopy

[Icons]
Name: "{autoprograms}\BlameNet"; Filename: "{app}\{#AppExeName}.exe"
Name: "{autodesktop}\BlameNet"; Filename: "{app}\{#AppExeName}.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}.exe"; Description: "运行 BlameNet"; Flags: nowait postinstall skipifsilent

[Code]
// 目录选择页的“浏览”按钮改用 Windows 原生资源管理器（IFileDialog）
var
  NativeBrowseInstalled: Boolean;

// 判断某路径的最后一段是否已经是目标子目录（BlameNet-<版本号>）
function EndsWithAppDir(const Path: string): Boolean;
begin
  Result := (CompareText(ExtractFileName(RemoveBackslash(Path)), '{#AppExeName}') = 0);
end;

// 把路径归一化为“目标子目录”形态：若末尾不是 BlameNet-<版本号>，则追加
function EnsureAppSubDir(const Path: string): string;
begin
  if EndsWithAppDir(Path) then
    Result := AddBackslash(Path)
  else
    Result := AddBackslash(Path) + '{#AppExeName}';
end;

procedure NativeDirBrowseClick(Sender: TObject);
var
  ResultCode: Integer;
  TempExe, TempResult: string;
  SelectedPath: string;
  Lines: TArrayOfString;
begin
  if not NativeBrowseInstalled then
  begin
    ExtractTemporaryFile('FolderPicker.exe');
    NativeBrowseInstalled := True;
  end;
  TempExe := ExpandConstant('{tmp}\FolderPicker.exe');
  TempResult := ExpandConstant('{tmp}\folder-result.txt');
  DeleteFile(TempResult);
  if not Exec(TempExe, '"' + TempResult + '" "选择安装文件夹"', '', SW_SHOW, ewWaitUntilTerminated, ResultCode) then
  begin
    MsgBox('无法启动文件夹选择器。', mbError, MB_OK);
    Exit;
  end;
  SelectedPath := '';
  if FileExists(TempResult) and LoadStringsFromFile(TempResult, Lines) then
  begin
    if GetArrayLength(Lines) > 0 then
      SelectedPath := Lines[0];
  end;
  if SelectedPath <> '' then
    // 关键：在用户选择的目录下自动追加“BlameNet-<版本号>”子目录，
    // 保证选 \desktop 时文件落在 \desktop\BlameNet-<版本号>\ 而非直接散落到 desktop
    WizardForm.DirEdit.Text := EnsureAppSubDir(SelectedPath);
  DeleteFile(TempResult);
end;

procedure InitializeWizard();
begin
  NativeBrowseInstalled := False;
  WizardForm.DirBrowseButton.OnClick := @NativeDirBrowseClick;
  // 关键：默认安装路径也必须落在 BlameNet-<版本号> 子目录内。
  // AppendDefaultDirName 仅在点“浏览”时生效，对默认值本身不追加，
  // 这里显式归一化，保证“默认”与“手动选择”两种情况的目录结构一致。
  WizardForm.DirEdit.Text := EnsureAppSubDir(WizardForm.DirEdit.Text);
end;

