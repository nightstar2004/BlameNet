@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem  BlameNet  Windows installer (.exe)  build script
rem ============================================================
rem
rem  [INPUT]
rem    pom.xml                            -> project version
rem    src/                               -> Java source
rem    installer.iss                      -> Inno Setup script
rem    build-tools/FolderPicker/          -> native folder picker (C#)
rem
rem  [INTERMEDIATE]
rem    target/BlameNet.jar                -> fat jar (by Maven)
rem    target/jpackage-input/             -> jpackage input (main jar only)
rem    target/app-image/                  -> jpackage green folder
rem    build-tools/FolderPicker/out/      -> FolderPicker.exe (single-file)
rem
rem  [OUTPUT]
rem    target/exe/BlameNet-Setup-<version>.exe
rem
rem  [USAGE]
rem    Double-click this script, or run:  package-exe.bat
rem
rem ============================================================

rem ---- Config (edit as needed) ----
set "JDK_HOME=C:\Users\night\.jdks\openjdk-22.0.1"
set "MVN=D:\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd"
set "JPACKAGE=%JDK_HOME%\bin\jpackage.exe"
set "ISCC=D:\IntelliJ IDEA 2025.1\lib\Inno Setup 6\ISCC.exe"

cd /d "%~dp0"

rem ---- Step 1. Read version from pom.xml (first <version> tag) ----
set "VERSION="
for /f "tokens=3 delims=<>" %%v in ('findstr /c:"<version>" pom.xml') do (
    if not defined VERSION set "VERSION=%%v"
)
if "%VERSION%"=="" (
    echo [ERROR] Cannot read version from pom.xml
    exit /b 1
)
echo Version: %VERSION%

rem jpackage --app-version only accepts numeric x.y.z; strip any -suffix (e.g. 0.1-alpha -> 0.1)
set "APP_VERSION=%VERSION%"
for /f "tokens=1 delims=-" %%v in ("%VERSION%") do set "APP_VERSION=%%v"

rem ---- Step 2. Build fat jar  (output: target\BlameNet.jar) ----
echo [1/5] Building BlameNet.jar ...
call "%MVN%" -q -DskipTests package
if errorlevel 1 (
    echo [ERROR] mvn package failed
    exit /b 1
)

rem ---- Step 3. Prepare clean input dir (only main jar) ----
echo [2/5] Preparing jpackage input dir ...
set "INPUT_DIR=%~dp0target\jpackage-input"
if exist "%INPUT_DIR%" rmdir /s /q "%INPUT_DIR%"
mkdir "%INPUT_DIR%"
copy /y "%~dp0target\BlameNet.jar" "%INPUT_DIR%\" >nul

rem ---- Step 4. jpackage -> app-image  (output: target\app-image\BlameNet-<version>\) ----
echo [3/5] Generating app-image (first run needs jlink) ...
set "MODULES=java.desktop,java.management,java.naming,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml.crypto,jdk.unsupported"

set "APP_IMAGE_DIR=%~dp0target\app-image"
if exist "%APP_IMAGE_DIR%" rmdir /s /q "%APP_IMAGE_DIR%"

"%JPACKAGE%" ^
  --type app-image ^
  --name "BlameNet-%VERSION%" ^
  --app-version "%APP_VERSION%" ^
  --input "%INPUT_DIR%" ^
  --main-jar BlameNet.jar ^
  --main-class com.blamenet.Launcher ^
  --vendor "BlameNet" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Dstdout.encoding=UTF-8" ^
  --java-options "-Dstderr.encoding=UTF-8" ^
  --dest "%APP_IMAGE_DIR%" ^
  --add-modules "%MODULES%"

if errorlevel 1 (
    echo [ERROR] jpackage failed, see output above
    exit /b 1
)

rem ---- Step 5. Build FolderPicker.exe (native folder picker for installer) ----
echo [4/5] Building native folder picker ...
dotnet publish "%~dp0build-tools\FolderPicker\FolderPicker.csproj" -c Release -o "%~dp0build-tools\FolderPicker\out" -v q
if errorlevel 1 (
    echo [ERROR] FolderPicker build failed
    exit /b 1
)

rem ---- Step 6. Inno Setup -> exe installer  (output: target\exe\BlameNet-Setup-<version>.exe) ----
echo [5/5] Generating installer with Inno Setup ...
"%ISCC%" /DAppVersion="%APP_VERSION%" /DFullVersion="%VERSION%" "%~dp0installer.iss"
if errorlevel 1 (
    echo [ERROR] Inno Setup failed, see output above
    exit /b 1
)

echo.
echo ============================================================
echo Installer generated: target\exe\BlameNet-Setup-%VERSION%.exe
echo During install, pick a dir (e.g. Desktop); files go to:
echo   ^<chosen dir^>\BlameNet-%VERSION%\
echo ============================================================
endlocal
