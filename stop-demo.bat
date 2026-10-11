@echo off
rem Stops what start-demo.bat started: the application, the demo MCP server and the MongoDB container.
rem The database data is kept (Docker volumes), so the next start is fast.
setlocal EnableExtensions
cd /d "%~dp0"
title Agent Framework - stop

echo Stopping the application and the demo MCP server...
for %%p in (8080 8099) do (
  for /f "tokens=5" %%i in ('netstat -ano ^| findstr /c:":%%p " ^| findstr /c:"LISTENING"') do (
    taskkill /pid %%i /t /f >nul 2>&1
  )
)
taskkill /fi "WINDOWTITLE eq Agent Framework app*" /t /f >nul 2>&1
taskkill /fi "WINDOWTITLE eq Demo MCP server*" /t /f >nul 2>&1

echo Stopping MongoDB...
docker compose stop >nul 2>&1

echo Done.
if not defined NO_PAUSE pause
