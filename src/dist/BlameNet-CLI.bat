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

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] PATH 中未找到 java.exe。
    echo        请安装 Oracle OpenJDK 22.0.1 并将其 bin 目录加入 PATH。
    pause
    exit /b 1
)

rem 统一为 UTF-8 控制台，避免中文在 GBK 代码页下乱码
chcp 65001 >nul 2>nul

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" --cli %*
set "RC=%ERRORLEVEL%"

echo.
echo ---------------------------------------------------------------
echo BlameNet 命令行已完成，退出码：%RC%
echo 报告输出目录：%~dp0results\
echo ---------------------------------------------------------------
pause
exit /b %RC%
