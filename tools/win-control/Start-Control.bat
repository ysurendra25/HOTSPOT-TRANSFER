@echo off
setlocal
cd /d "%~dp0"
if not exist pc-remote.exe (
  echo pc-remote.exe is missing. Extract the complete PocketBridge Windows Control ZIP first.
  pause
  exit /b 1
)
start "" /b pc-remote.exe
set /a tries=0
:wait_for_link
set "LINE="
if exist pc-remote.log for /f "usebackq delims=" %%L in (`findstr /c:"LAN: http" pc-remote.log`) do set "LINE=%%L"
if not "%LINE%"=="" goto show_link
set /a tries+=1
if %tries% geq 25 goto no_link
timeout /t 1 /nobreak >nul
goto wait_for_link
:show_link
for /f "tokens=5" %%U in ("%LINE%") do set "URL=%%U"
echo.
echo PocketBridge PC control is ready.
echo On your phone, while connected to the PocketBridge hotspot, open this address:
echo %URL%
echo.
echo Keep this window open while you control the laptop. Press any key here to stop it.
pause >nul
taskkill /im pc-remote.exe /f >nul 2>nul
exit /b 0
:no_link
echo The helper did not report a LAN link. Check pc-remote.log and Windows Firewall.
echo Allow access only on Private networks if Windows asks.
pause
exit /b 1

