@echo off
REM ============================================================
REM   健康管家全功能模拟一键运行
REM   在终端里逐条打印模拟"发消息"与机器人回复
REM ============================================================

REM 切到脚本所在目录的根（脚本放在仓库根）
cd /d "%~dp0"

REM 切到 UTF-8，避免中文乱码
chcp 65001 >nul 2>&1

echo.
echo ============================================================
echo   健康管家全功能模拟开始（每条消息都会打印出来）
echo ============================================================
echo.

REM 从根目录运行 -pl bot，避免 "Could not find the selected project" 报错
mvn -pl bot "-Dexec.classpathScope=test" "-Dexec.mainClass=com.github.wechat.cloudbot.bootstrap.HealthFeatureSimulationMain" exec:java

echo.
echo ============================================================
echo   模拟结束
echo ============================================================
pause
