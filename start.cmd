@echo off
setlocal
set "PSModulePath=%SystemRoot%\System32\WindowsPowerShell\v1.0\Modules"
chcp 65001 >nul
if not exist "%~dp0deploy\one-click.ps1" (
  echo 请先完整下载并解压项目。
  pause
  exit /b 1
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\one-click.ps1" -Action Start %*
set "SCENT_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %SCENT_RESULT%
