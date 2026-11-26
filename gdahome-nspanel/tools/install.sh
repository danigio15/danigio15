#!/usr/bin/env bash
# Installa GDAHome su un Sonoff NSPanel Pro / NSPanel Pro 120 via ADB (Wi-Fi, porta 5555).
#
# Uso:
#   ./install.sh <IP-pannello> [file.apk]                  installa e imposta GDAHome come launcher
#   ./install.sh <IP-pannello> [file.apk] --disable-sonoff  ...e disattiva le app Sonoff/eWeLink
#
# Le app Sonoff vengono solo DISATTIVATE (non cancellate): restore.sh le riattiva.
set -euo pipefail

IP="${1:?Indica IP del pannello, es: ./install.sh 192.168.1.50}"
APK="${2:-GDAHome.apk}"
DISABLE=0
for a in "$@"; do [[ "$a" == "--disable-sonoff" ]] && DISABLE=1; done
[[ "$APK" == "--disable-sonoff" ]] && APK="GDAHome.apk"

PKG="it.gdahome.panel"
HOME_ACT="$PKG/.ui.MainActivity"
DEV="$IP:5555"
A() { adb -s "$DEV" "$@"; }

echo "==> Connessione a $DEV"
adb connect "$DEV"
A wait-for-device

echo "==> Modello: $(A shell getprop ro.product.model | tr -d '\r')  Android $(A shell getprop ro.build.version.release | tr -d '\r')"

echo "==> Installazione $APK"
A install -r "$APK"

echo "==> GDAHome come launcher predefinito"
A shell cmd package set-home-activity "$HOME_ACT" 2>/dev/null \
  || A shell pm set-home-activity "$HOME_ACT" 2>/dev/null \
  || echo "   (impostalo a mano: premi Home sul pannello e scegli GDAHome → Sempre)"

echo "==> Schermo sempre acceso a livello Android (lo spegnimento lo gestisce GDAHome)"
A shell settings put system screen_off_timeout 2147483647
A shell settings put global stay_on_while_plugged_in 7 || true
A shell appops set "$PKG" WRITE_SETTINGS allow || true
A shell dumpsys deviceidle whitelist +"$PKG" >/dev/null || true

echo "==> App Sonoff/eWeLink trovate sul pannello:"
SONOFF=$(A shell pm list packages | tr -d '\r' | sed 's/^package://' \
  | grep -iE 'sonoff|ewelink|itead|coolkit|smatek' | grep -v "^$PKG$" || true)
if [[ -z "$SONOFF" ]]; then echo "   nessuna"; else echo "$SONOFF" | sed 's/^/   /'; fi

if [[ $DISABLE -eq 1 && -n "$SONOFF" ]]; then
  BACKUP="sonoff-disabilitate-$IP.txt"
  echo "==> Disattivo le app Sonoff (elenco salvato in $BACKUP)"
  : > "$BACKUP"
  for p in $SONOFF; do
    if A shell pm disable-user --user 0 "$p" | grep -q "disabled"; then
      echo "$p" >> "$BACKUP"; echo "   disattivata: $p"
    else
      echo "   NON disattivata: $p"
    fi
  done
fi

echo "==> Avvio GDAHome"
A shell am start -n "$HOME_ACT" >/dev/null
A shell input keyevent 3
echo
echo "Fatto. Tieni premuto il logo GDAHome per entrare nelle impostazioni (PIN predefinito 1234)."
[[ $DISABLE -eq 0 ]] && echo "Le app Sonoff sono ancora installate: quando sei soddisfatto rilancia con --disable-sonoff."
