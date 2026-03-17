@echo off
setlocal
set "BASE_DIR=%~dp0.."
java -jar "%BASE_DIR%\lib\jaipilot.jar" %*
exit /b %ERRORLEVEL%
