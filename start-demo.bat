@echo off
rem One-click local start: MongoDB (Docker), corpus, demo MCP server, the application, and the browser.
rem Double-click this file. Needs: Java 21, Docker Desktop (running), Git for Windows (bash, for the corpus
rem download on the first run) and a Gemini key in .env. Run stop-demo.bat to stop everything.
rem Environment switches for scripted use: NO_BROWSER=1 (do not open the browser), NO_PAUSE=1.
setlocal EnableExtensions
cd /d "%~dp0"
title Agent Framework - start

echo.
echo ================================================
echo   Agent Framework - one-click local start
echo ================================================
echo.

rem ---- prerequisites --------------------------------------------------------------------------------
where java >nul 2>&1
if errorlevel 1 (
  echo [X] Java was not found on PATH. Install Java 21, for example Temurin 21, and run this again.
  goto :fail
)
where docker >nul 2>&1
if errorlevel 1 (
  echo [X] Docker was not found. Install Docker Desktop and run this again.
  goto :fail
)
docker info >nul 2>&1
if errorlevel 1 (
  echo [X] Docker is not running. Start Docker Desktop, wait until it is ready, then run this again.
  goto :fail
)
if not exist ".env" (
  copy ".env.example" ".env" >nul
  echo [!] .env was created. Put your Gemini key after GOOGLE_API_KEY= and save the file.
  notepad ".env"
  goto :fail
)
set "KEYOK="
for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
  if "%%a"=="GOOGLE_API_KEY" if not "%%b"=="" if not "%%b"=="your-gemini-api-key" set "KEYOK=1"
)
if not defined KEYOK (
  echo [X] GOOGLE_API_KEY in .env is empty. Put your Gemini key in it and run this again.
  notepad ".env"
  goto :fail
)

rem ---- 1. MongoDB ------------------------------------------------------------------------------------
echo [1/5] Starting MongoDB in Docker...
docker compose up -d >nul 2>&1
if errorlevel 1 (
  echo [X] docker compose up failed. Run "docker compose up -d" in this folder to see why.
  goto :fail
)
set "MONGO_ID="
for /f %%i in ('docker compose ps -q mongodb') do set "MONGO_ID=%%i"
set /a TRIES=0
:waitmongo
set "HEALTH="
for /f %%h in ('docker inspect --format "{{.State.Health.Status}}" %MONGO_ID% 2^>nul') do set "HEALTH=%%h"
if "%HEALTH%"=="healthy" goto :mongook
set /a TRIES+=1
if %TRIES% GEQ 60 (
  echo [X] MongoDB did not become healthy in 5 minutes.
  goto :fail
)
timeout /t 5 /nobreak >nul
goto :waitmongo
:mongook
echo       MongoDB is healthy.

rem ---- 2. corpus -------------------------------------------------------------------------------------
if not exist "corpus\*.pdf" (
  echo [2/5] Downloading the 10 corpus PDFs, about 40 MB, with checksums...
  where bash >nul 2>&1
  if errorlevel 1 (
    echo [X] bash was not found. Install Git for Windows, or run scripts\fetch-corpus.sh yourself.
    goto :fail
  )
  bash scripts/fetch-corpus.sh
  if errorlevel 1 goto :fail
) else (
  echo [2/5] Corpus PDFs are present.
)

rem ---- 3. demo MCP server ----------------------------------------------------------------------------
netstat -ano | findstr /c:":8099 " | findstr /c:"LISTENING" >nul
if errorlevel 1 (
  echo [3/5] Starting the demo MCP server on port 8099...
  start "Demo MCP server" /min java src\test\java\com\demo\agentfw\mcp\DemoMcpServer.java --port 8099 --token demo-secret
) else (
  echo [3/5] The demo MCP server is already running.
)

rem ---- 4. the application ----------------------------------------------------------------------------
set "AGENTFW_MCP_ALLOWED_HOSTS=localhost"
set "AGENTFW_INGESTION_ON_STARTUP=true"
set "CODE=0"
for /f %%s in ('powershell -NoProfile -Command "try { (Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health -TimeoutSec 3).StatusCode } catch { 0 }"') do set "CODE=%%s"
if "%CODE%"=="200" (
  echo [4/5] The application is already running.
  goto :open
)
echo [4/5] Starting the application in its own window. The first start takes a few minutes: it builds,
echo       downloads the rerank model, and embeds the documents if the database is empty.
start "Agent Framework app" cmd /k "mvnw.cmd spring-boot:run"
set /a TRIES=0
:waitapp
set "CODE=0"
for /f %%s in ('powershell -NoProfile -Command "try { (Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health -TimeoutSec 3).StatusCode } catch { 0 }"') do set "CODE=%%s"
if "%CODE%"=="200" goto :open
set /a TRIES+=1
if %TRIES% GEQ 90 (
  echo [X] The application did not become healthy in 15 minutes. Look at the "Agent Framework app" window.
  goto :fail
)
timeout /t 10 /nobreak >nul
goto :waitapp

:open
echo [5/5] Ready: http://localhost:8080
if not defined NO_BROWSER start "" "http://localhost:8080"
echo.
echo ------------------------------------------------
echo  To try scenario D, open "MCP settings" in the app and add:
echo     URL        http://localhost:8099/mcp
echo     Transport  Streamable HTTP
echo     Header     Authorization = Bearer demo-secret
echo  Then ask: Where is order ORD-1001?
echo.
echo  If this is the first start on this machine, the documents are embedded in the background;
echo  wait until the app window says "Ingestion finished" before asking about the PDFs.
echo.
echo  Stop everything with stop-demo.bat.
echo ------------------------------------------------
if not defined NO_PAUSE pause
exit /b 0

:fail
echo.
echo Start was not completed.
if not defined NO_PAUSE pause
exit /b 1
