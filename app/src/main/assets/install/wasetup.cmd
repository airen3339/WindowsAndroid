@echo off
rem WindowsAndroid: run from the answer disc in the specialize pass of a new installation.
rem Installs the virtio-win NetKVM network driver and the display helper (fit-display.ps1).
set SRC=%~d0\
mkdir C:\Windows\WindowsAndroid 2>nul
copy /y %SRC%FITDISP.PS1 C:\Windows\WindowsAndroid\fit-display.ps1 >nul
pnputil /add-driver %SRC%NETKVM.INF /install > C:\Windows\WindowsAndroid\netkvm.log 2>&1
reg add HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run /v WindowsAndroidFitDisplay /t REG_SZ /d "conhost.exe --headless powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\Windows\WindowsAndroid\fit-display.ps1" /f >nul
exit /b 0
