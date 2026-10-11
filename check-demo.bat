@echo off
rem One-click live checks against the running application (start-demo.bat first). These make real
rem model calls, about 25 in total. It runs, in order:
rem   1. the routing check: reference questions go to the RAG agent, file requests to the file agent
rem   2. the MCP check: both transports, the error messages, a tool answer and a tool error
setlocal EnableExtensions
cd /d "%~dp0"
title Agent Framework - live checks

where python >nul 2>&1
if errorlevel 1 (
  echo [X] Python was not found on PATH. Install Python 3 and run this again.
  goto :end
)
set "CODE=0"
for /f %%s in ('powershell -NoProfile -Command "try { (Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health -TimeoutSec 3).StatusCode } catch { 0 }"') do set "CODE=%%s"
if not "%CODE%"=="200" (
  echo [X] The application is not running on port 8080. Run start-demo.bat first.
  goto :end
)

echo ================================================
echo  1. Routing check: 10 cases
echo ================================================
python scripts\live-routing-check.py scripts\sample-notes.txt
echo.
echo ================================================
echo  2. MCP check: needs the demo server on port 8099
echo ================================================
python scripts\live-mcp-check.py

:end
echo.
if not defined NO_PAUSE pause
