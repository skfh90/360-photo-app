@echo off
setlocal
cd /d "%~dp0"

if not exist "offline-gradle-6.7.7z.001" (
  echo Missing offline-gradle-6.7.7z.001
  exit /b 1
)

set "SEVEN="
if exist "%ProgramFiles%\7-Zip\7z.exe" set "SEVEN=%ProgramFiles%\7-Zip\7z.exe"
if exist "%ProgramFiles(x86)%\7-Zip\7z.exe" set "SEVEN=%ProgramFiles(x86)%\7-Zip\7z.exe"
if "%SEVEN%"=="" (
  echo 7-Zip is required to unpack Gradle 6.7 and this project's caches.
  echo Install it from https://www.7-zip.org/ then re-run this script.
  exit /b 1
)

echo Extracting Gradle 6.7 and project caches into offline\ ...
"%SEVEN%" x -y -o. "offline-gradle-6.7.7z.001"
if errorlevel 1 exit /b 1
echo Done. Wrapper zip and maven-repo are under offline\
endlocal
