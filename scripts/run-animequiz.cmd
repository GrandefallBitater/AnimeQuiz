@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Launch the packaged AnimeQuiz app with local secrets passed as
rem Spring Boot command-line arguments.

set "SECRETS=%USERPROFILE%\.animequiz\secrets.properties"
if not exist "%SECRETS%" (
    echo Secrets file not found:
    echo   %SECRETS%
    echo Create it with spring.datasource.password, app.yummy.app-token
    echo and app.mal.client-id, then run this launcher again.
    pause
    exit /b 1
)

set "APP_ARGS="
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%SECRETS%") do (
    if not "%%B"=="" set "APP_ARGS=!APP_ARGS! "--%%A=%%B""
)

cd /d "%~dp0.."
"AnimeQuiz.exe" %APP_ARGS%
