@echo off
rem One-click unit tests: ./mvnw verify. No Gemini key and no network are needed. MongoDB tests are
rem skipped if the MongoDB container is not running (start-demo.bat starts it).
setlocal EnableExtensions
cd /d "%~dp0"
title Agent Framework - tests

echo Running the tests. This takes a few minutes the first time.
echo.
call mvnw.cmd verify
set "RESULT=%ERRORLEVEL%"
echo.
if "%RESULT%"=="0" (
  echo ================ ALL TESTS PASSED ================
) else (
  echo ================ TESTS FAILED, see above ================
)
if not defined NO_PAUSE pause
exit /b %RESULT%
