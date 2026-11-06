@echo off
setlocal EnableExtensions EnableDelayedExpansion
for /f "tokens=2 delims=: " %%C in ('chcp') do set "CP=%%C"
set "CP=!CP: =!"
if "!CP!"=="65001" (
    set "LOG_CHARSET=UTF-8"
    set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 %JAVA_TOOL_OPTIONS%"
) else (
    set "LOG_CHARSET=GBK"
    set "JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK -Dsun.stdout.encoding=GBK -Dsun.stderr.encoding=GBK %JAVA_TOOL_OPTIONS%"
)
title Cloudbot

echo ========================================
echo  Cloudbot 启动脚本
echo  将自动杀掉旧进程后启动新实例
echo ========================================

REM 查找并杀掉所有 cloudbot-bot 的 Java 进程（jps 是 JDK 自带）
echo [1/2] 检查并清理旧进程...
for /f "tokens=1" %%i in ('jps -l 2^>nul ^| findstr cloudbot-bot') do (
    echo   - 发现旧进程 PID: %%i，正在杀掉...
    taskkill /F /PID %%i >nul 2>&1
)

REM 等 2 秒确保进程完全退出
timeout /t 2 /nobreak >nul

REM 启动新实例
echo [2/2] 启动新 Bot 实例...
echo ========================================
cd /d "%~dp0bot"
mvn spring-boot:run
if errorlevel 1 (
    echo.
    echo 启动失败，按任意键退出
    pause >nul
)
