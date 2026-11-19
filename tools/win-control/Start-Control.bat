@echo off
setlocal
cd /d "%~dp0"
rem The release ZIP ships the helper as PocketBridge-Windows-Control.exe, while the
rem build output is called pc-remote.exe. Accept either name, because a mismatch here
rem stops the helper from ever starting and the phone then has nothing to connect to.
set "HELPER="
if exist "PocketBridge-Windows-Control.exe" set "HELPER=PocketBridge-Windows-Control.exe"
if not defined HELPER if exist "pc-remote.exe" set "HELPER=pc-remote.exe"
if not defined HELPER (
  echo The helper program is missing from this folder.
  echo Extract the complete PocketBridge Windows Control ZIP and run this file again.
  pause
  exit /b 1
)
rem Windows blocks inbound port 7070 by default. Add a Private-network rule once so
rem the phone can reach the helper without depending on the first-run prompt, which
rem is easy to dismiss and then blocks every connection silently.
netsh advfirewall firewall show rule name="PocketBridge PC control" >nul 2>nul
if errorlevel 1 (
  netsh advfirewall firewall add rule name="PocketBridge PC control" dir=in action=allow protocol=TCP localport=7070 profile=private >nul 2>nul
  if errorlevel 1 (
    echo.
    echo Windows Firewall is blocking port 7070 and this window is not elevated.
    echo Right-click Start-Control.bat and choose "Run as administrator" once, or
    echo allow PocketBridge on Private networks when Windows asks.
    echo.
  )
)
rem The helper rewrites pc-remote-links.txt on every start. Delete the old copy
rem first so a link from an earlier run (with an old address) is never shown.
if exist pc-remote-links.txt del /q pc-remote-links.txt >nul 2>nul
start "" /b "%HELPER%"
set /a tries=0
:wait_for_link
if exist pc-remote-links.txt goto show_link
set /a tries+=1
if %tries% geq 25 goto no_link
timeout /t 1 /nobreak >nul
goto wait_for_link
:show_link
echo.
echo PocketBridge PC control is ready.
echo Paste ONE of these links into PocketBridge on the phone (Connect tab,
echo Windows helper link). The first one is usually right; if the phone cannot
echo reach it, PocketBridge also searches the hotspot for this laptop by itself.
echo.
type pc-remote-links.txt
echo.
echo Keep this window open while you control the laptop. Press any key here to stop it.
pause >nul
taskkill /im "%HELPER%" /f >nul 2>nul
taskkill /im pc-remote.exe /f >nul 2>nul
exit /b 0
:no_link
echo The helper did not report a LAN link. Join the phone's hotspot first, check
echo pc-remote.log, then allow port 7070:
echo   netsh advfirewall firewall add rule name="PocketBridge PC control" dir=in action=allow protocol=TCP localport=7070 profile=private
echo Allow access only on Private networks if Windows asks.
pause
exit /b 1

