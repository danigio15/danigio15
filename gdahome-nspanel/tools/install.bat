@echo off
REM GdAHome OS - installazione leggera da Windows.  Uso: install.bat 192.168.1.50
REM Servono GdAHome.apk (app ufficiale) e GdAHome-OS.apk nella stessa cartella.
setlocal
set IP=%1
if "%IP%"=="" ( echo Uso: install.bat IP-pannello & exit /b 1 )
set DEV=%IP%:5555
adb connect %DEV%
adb -s %DEV% wait-for-device
adb -s %DEV% install -r -g "%~dp0GdAHome.apk"
adb -s %DEV% install -r "%~dp0GdAHome-OS.apk"
adb -s %DEV% shell pm uninstall it.gdahome.panel
adb -s %DEV% shell cmd package set-home-activity com.gdahome.os/.ui.AvvioActivity
adb -s %DEV% shell settings put system screen_off_timeout 2147483647
adb -s %DEV% shell settings put global stay_on_while_plugged_in 7
adb -s %DEV% shell dumpsys deviceidle whitelist +com.gdahome.os
adb -s %DEV% shell dumpsys deviceidle whitelist +com.gdahome.gdahome
adb -s %DEV% shell am start -n com.gdahome.os/.ui.AvvioActivity
echo Fatto. Abbina GdAHome alla casa e scegli la plancia "A muro".
