@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "JAR=%~dp0BlameNet.jar"

if not exist "%JAR%" (
    echo [错误] 在 "%~dp0" 下未找到 BlameNet.jar。
    echo        请保持本脚本位于 BlameNet 安装目录内。
    pause
    exit /b 1
)

where javaw >nul 2>nul
if errorlevel 1 (
    echo [错误] PATH 中未找到 javaw.exe。
    echo        请安装 Oracle OpenJDK 22.0.1 并将其 bin 目录加入 PATH。
    pause
    exit /b 1
)

rem 图形界面使用 UTF-8 编码；界面内的“提权运行”按钮会在需要时以管理员身份重新启动
start "" javaw -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" %*
exit /b 0
