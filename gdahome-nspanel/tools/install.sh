#!/usr/bin/env bash
# GdAHome OS — installazione leggera (senza toccare la partizione di sistema).
# Utile per provare: installa l'app GdAHome e GdAHome OS come app normali,
# imposta l'avvio GdAHome come schermata home e, se vuoi, DISATTIVA le app Sonoff.
#
# Uso:
#   ./install.sh <IP-pannello>                    GdAHome + GdAHome OS, Sonoff resta
#   ./install.sh <IP-pannello> --disable-sonoff   ...e disattiva le app Sonoff
# Nella cartella servono GdAHome.apk (app ufficiale) e GdAHome-OS.apk.
# Per il sistema completo usa gdahome-os.sh.
set -euo pipefail

IP="${1:?Indica IP del pannello, es: ./install.sh 192.168.1.50}"
DISABLE=0; [[ "${2:-}" == "--disable-sonoff" ]] && DISABLE=1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OS_PKG="com.gdahome.os"; GDA="com.gdahome.gdahome"
HOME_ACT="$OS_PKG/.ui.AvvioActivity"
DEV="$IP:5555"
A() { adb -s "$DEV" "$@"; }

for f in GdAHome.apk GdAHome-OS.apk; do [[ -f "$HERE/$f" ]] || { echo "Manca $f in $HERE"; exit 1; }; done

echo "==> Connessione a $DEV"; adb connect "$DEV"; A wait-for-device
echo "==> $(A shell getprop ro.product.model | tr -d '\r'), Android $(A shell getprop ro.build.version.release | tr -d '\r')"
echo "==> App GdAHome";  A install -r -g "$HERE/GdAHome.apk"
echo "==> GdAHome OS";   A install -r "$HERE/GdAHome-OS.apk"
A shell pm uninstall it.gdahome.panel >/dev/null 2>&1 || true
A shell cmd package set-home-activity "$HOME_ACT" >/dev/null 2>&1 \
  || echo "   (impostala a mano: premi Home sul pannello e scegli GdAHome OS → Sempre)"
A shell settings put system screen_off_timeout 2147483647
A shell settings put global stay_on_while_plugged_in 7 || true
for p in $OS_PKG $GDA; do A shell dumpsys deviceidle whitelist +"$p" >/dev/null || true; done

SONOFF=$(A shell pm list packages | tr -d '\r' | sed 's/^package://' | grep -iE 'sonoff|ewelink|itead|coolkit|smatek' || true)
echo "==> App Sonoff/eWeLink:"; [[ -z "$SONOFF" ]] && echo "   nessuna" || echo "$SONOFF" | sed 's/^/   /'
if [[ $DISABLE -eq 1 && -n "$SONOFF" ]]; then
  BACKUP="sonoff-disabilitate-$IP.txt"; : > "$BACKUP"
  for p in $SONOFF; do A shell pm disable-user --user 0 "$p" | grep -q disabled && { echo "$p" >> "$BACKUP"; echo "   disattivata: $p"; }; done
fi
A shell am start -n "$HOME_ACT" >/dev/null
echo "Fatto. Sul pannello si apre GdAHome: abbinalo alla casa e scegli la plancia «A muro»."
