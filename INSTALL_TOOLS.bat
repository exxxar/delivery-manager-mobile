@echo off
chcp 65001 >nul
setlocal
if "%ANDROID_SDK_ROOT%"=="" if "%ANDROID_HOME%"=="" (
  echo Сначала задай ANDROID_SDK_ROOT, например:
  echo setx ANDROID_SDK_ROOT "D:\Android\Sdk"
  pause
  exit /b 1
)
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "SDKMANAGER=%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SDKMANAGER%" (
  echo Не найден sdkmanager: %SDKMANAGER%
  echo Распакуй Android Command-line Tools в cmdline-tools\latest
  pause
  exit /b 1
)
call "%SDKMANAGER%" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
call "%SDKMANAGER%" --licenses
pause
