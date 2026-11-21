@echo off
setlocal
cd /d "%~dp0"
set "HELPER=PocketBridge-Windows-Control.exe"
if not exist "%HELPER%" set "HELPER=pc-remote.exe"
if not exist "%HELPER%" (
  echo Extract the complete PocketBridge Windows Control ZIP, then open Start-Control.bat again.
  pause
  exit /b 1
)
echo PocketBridge Windows Control
echo Connect the laptop to the phone's hotspot. If Windows asks, allow it on your private network.
echo Paste the LAN link for the Wi-Fi adapter into PocketBridge on the phone.
echo Keep this window open while sharing. Close this window to stop its helper.
echo.
"%~dp0%HELPER%"
if errorlevel 1 (
  echo.
  echo Check the message above. If PocketBridge is already running, use its original window.
  pause
  exit /b 1
)
