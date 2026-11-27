#!/usr/bin/env bash
# Riattiva le app Sonoff disattivate da install.sh e ripristina il pannello originale.
# Uso: ./restore.sh <IP-pannello> [--uninstall-gdahome]
set -euo pipefail
IP="${1:?Indica IP del pannello}"
DEV="$IP:5555"
A() { adb -s "$DEV" "$@"; }
adb connect "$DEV"; A wait-for-device

BACKUP="sonoff-disabilitate-$IP.txt"
if [[ -f "$BACKUP" ]]; then
  PKGS=$(cat "$BACKUP")
else
  echo "File $BACKUP non trovato: riattivo tutte le app Sonoff/eWeLink disattivate"
  PKGS=$(A shell pm list packages -d | tr -d '\r' | sed 's/^package://' | grep -iE 'sonoff|ewelink|itead|coolkit|smatek' || true)
fi
for p in $PKGS; do A shell pm enable "$p" && echo "riattivata: $p"; done

A shell settings put system screen_off_timeout 60000
if [[ "${2:-}" == "--uninstall-gdahome" ]]; then A shell pm uninstall com.gdahome.os || true; fi
echo "Riavvio del pannello…"; A reboot
