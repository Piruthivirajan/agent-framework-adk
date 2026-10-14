@echo off
rem Stops what start-demo.bat started: the application, the demo MCP server and the MongoDB container.
rem The database data is kept (Docker volumes), so the next start is fast.
setlocal EnableExtensions
cd /d "%~dp0"
title Agent Framework - stop

echo Stopping the application and the demo MCP server...

rem Prefer PowerShell: netstat token parsing breaks on some Windows builds; also kill by command line.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='SilentlyContinue';" ^
  "foreach ($port in 8080,8099) {" ^
  "  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |" ^
  "    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }" ^
  "};" ^
  "Get-CimInstance Win32_Process | Where-Object {" ^
  "  $_.Name -eq 'java.exe' -and $_.ProcessId -ne $PID -and $_.CommandLine -match 'DemoMcpServer|spring-boot|AgentFrameworkApplication'" ^
  "} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

rem Close the windows start-demo.bat opened (taskkill WINDOWTITLE does not support * wildcards).
taskkill /FI "WINDOWTITLE eq Agent Framework app" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Demo MCP server" /T /F >nul 2>&1

echo Stopping MongoDB...
docker compose stop
if errorlevel 1 (
  echo [!] docker compose stop failed. Is Docker running?
) else (
  echo       MongoDB container stopped.
)

set "STILL="
for %%p in (8080 8099) do (
  netstat -ano | findstr /c:":%%p " | findstr /c:"LISTENING" >nul 2>&1
  if not errorlevel 1 set "STILL=1"
)
if defined STILL (
  echo [!] Ports 8080 or 8099 are still in use. Close any leftover Java or "Agent Framework app" window,
  echo     or run Task Manager and end java.exe on those ports.
) else (
  echo Done. Ports 8080 and 8099 are free.
)

if defined STILL (
  pause
  exit 1
)
exit 0
