@echo off
REM Installa GDAHome su NSPanel Pro da Windows.  Uso: install.bat 192.168.1.50 [GDAHome.apk]
REM Per disattivare le app Sonoff usa install.sh (Git Bash / WSL) con --disable-sonoff.
setlocal
set IP=%1
if "%IP%"=="" ( echo Uso: install.bat IP-pannello [file.apk] & exit /b 1 )
set APK=%2
if "%APK%"=="" set APK=GDAHome.apk
set DEV=%IP%:5555
adb connect %DEV%
adb -s %DEV% wait-for-device
adb -s %DEV% install -r "%APK%"
adb -s %DEV% shell cmd package set-home-activity it.gdahome.panel/.ui.MainActivity
adb -s %DEV% shell settings put system screen_off_timeout 2147483647
adb -s %DEV% shell settings put global stay_on_while_plugged_in 7
adb -s %DEV% shell appops set it.gdahome.panel WRITE_SETTINGS allow
adb -s %DEV% shell dumpsys deviceidle whitelist +it.gdahome.panel
echo App Sonoff/eWeLink presenti:
adb -s %DEV% shell "pm list packages | grep -iE 'sonoff|ewelink|itead|coolkit|smatek'"
adb -s %DEV% shell am start -n it.gdahome.panel/.ui.MainActivity
echo Fatto. Tieni premuto il logo per le impostazioni (PIN 1234).
