@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Courier Mobile - APK Build

echo ==============================================
echo   COURIER MOBILE - ANDROID APK BUILD
echo ==============================================

if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME не задан. Установи JDK 17 или 21 и укажи JAVA_HOME.
  pause
  exit /b 1
)
if "%ANDROID_SDK_ROOT%"=="" if "%ANDROID_HOME%"=="" (
  echo [ERROR] Не задан ANDROID_SDK_ROOT или ANDROID_HOME.
  echo Например: setx ANDROID_SDK_ROOT "D:\Android\Sdk"
  pause
  exit /b 1
)
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not exist "%ANDROID_SDK_ROOT%\platforms\android-35\android.jar" (
  echo [ERROR] Не найден Android SDK Platform 35: %ANDROID_SDK_ROOT%
  echo Установи платформу Android 35 через sdkmanager.
  pause
  exit /b 1
)

set "GRADLE_VERSION=8.9"
set "GRADLE_HOME=%~dp0.tools\gradle-%GRADLE_VERSION%"
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo [INFO] Gradle не найден. Загружаю Gradle %GRADLE_VERSION%...
  if not exist "%~dp0.tools" mkdir "%~dp0.tools"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%~dp0.tools\gradle.zip'; Expand-Archive -Force '%~dp0.tools\gradle.zip' '%~dp0.tools'; Remove-Item '%~dp0.tools\gradle.zip'"
  if errorlevel 1 (
    echo [ERROR] Не удалось загрузить Gradle. Проверь интернет.
    pause
    exit /b 1
  )
)

if not exist "local.properties" (
  >local.properties echo sdk.dir=%ANDROID_SDK_ROOT:\=/%
)

call "%GRADLE_HOME%\bin\gradle.bat" --no-daemon clean assembleDebug --stacktrace
if errorlevel 1 (
  echo.
  echo [ERROR] Сборка завершилась с ошибкой. См. вывод выше.
  pause
  exit /b 1
)

if not exist "dist" mkdir dist
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "dist\CourierMobile-debug.apk" >nul

echo.
echo [SUCCESS] APK создан:
echo %~dp0dist\CourierMobile-debug.apk
echo.
echo Установка на подключённый телефон: adb install -r dist\CourierMobile-debug.apk
pause
