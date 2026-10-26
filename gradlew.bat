@echo off
REM MagnetRute Windows Bootstrap fuer Gradle 8.9 ohne offizielle Wrapper-JAR
setlocal
set "CACHE=%USERPROFILE%\.gradle\magnetrute-bootstrap"
set "GRADLEBIN=%CACHE%\gradle-8.9\bin\gradle.bat"
if not exist "%GRADLEBIN%" (
  echo Lade Gradle 8.9 von services.gradle.org mit SHA256-Pruefung ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $base='%CACHE%'; New-Item -Force -ItemType Directory $base ^| Out-Null; $url='https://services.gradle.org/distributions/gradle-8.9-bin.zip'; $zip=Join-Path $base 'gradle.zip'; $sha=Join-Path $base 'gradle.sha256'; Invoke-WebRequest -Uri $url -OutFile $zip; Invoke-WebRequest -Uri ($url+'.sha256') -OutFile $sha; $expected=(Get-Content $sha -Raw).Trim().Split(' ')[0].ToLowerInvariant(); $actual=(Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant(); if ($expected -ne $actual) { throw 'Gradle ZIP checksum mismatch' }; Expand-Archive -Path $zip -DestinationPath $base -Force; Remove-Item $zip,$sha"
  if errorlevel 1 exit /b 1
)
call "%GRADLEBIN%" %*
exit /b %ERRORLEVEL%
