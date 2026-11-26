#!/usr/bin/env bash
# =============================================================================
#  GDAHome OS — trasforma un Sonoff NSPanel Pro / NSPanel Pro 120 in un pannello
#  GDAHome eliminando il software Sonoff dalla partizione di sistema.
#
#  Il kernel e i driver originali restano (display, touch, Wi-Fi, relè, Zigbee):
#  tutto lo strato software Sonoff/eWeLink viene rimosso da /system e GDAHome
#  diventa l'applicazione di sistema, con animazione di avvio e ponte Zigbee.
#
#  Comandi:
#    check   <IP>                verifica root, verity, partizioni; prepara l'elenco app Sonoff
#    backup  <IP> [--with-userdata]  copia TUTTE le partizioni sul PC (obbligatorio)
#    install <IP>                installa GDAHome OS (richiede check + backup)
#    restore <IP>                rimette il software Sonoff com'era
#    flash   <IP> <partizione> <file.img>   emergenza: riscrive una partizione dal backup
#
#  File richiesti nella stessa cartella (sono nello zip della build):
#    GDAHome.apk, gdaserial-armeabi-v7a, gdaserial-arm64-v8a, bootanimation.zip
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="it.gdahome.panel"
HOME_ACT="$PKG/.ui.MainActivity"
REMOVED="/system/gdahome-removed"
TMP="/data/local/tmp"
# Pacchetti che non vanno MAI rimossi (Android di base)
KEEP_RE='^(android|com\.android\..*|com\.google\.android\.webview|it\.gdahome\.panel)$'
# Pacchetti Sonoff/eWeLink riconosciuti automaticamente
SONOFF_RE='sonoff|ewelink|itead|coolkit|smatek|nspanel'

c_red=$'\e[31m'; c_grn=$'\e[32m'; c_yel=$'\e[33m'; c_bld=$'\e[1m'; c_off=$'\e[0m'
say()  { echo "${c_bld}==>${c_off} $*"; }
ok()   { echo "   ${c_grn}✔${c_off} $*"; }
warn() { echo "   ${c_yel}!${c_off} $*"; }
die()  { echo "${c_red}ERRORE:${c_off} $*" >&2; exit 1; }

usage() { sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1; }

[[ $# -ge 2 ]] || usage
CMD="$1"; IP="$2"; shift 2
DEV="$IP:5555"
A() { adb -s "$DEV" "$@"; }
clean() { tr -d '\r'; }

command -v adb >/dev/null || die "adb non trovato: installa Android platform-tools"

connect() {
  say "Connessione a $DEV"
  adb connect "$DEV" | clean | sed 's/^/   /'
  A wait-for-device
  MODEL=$(A shell getprop ro.product.model | clean)
  SERIAL=$(A shell getprop ro.serialno | clean)
  [[ -n "$SERIAL" ]] || SERIAL="${IP//./_}"
  DDIR="$PWD/gdahome-os-data/${MODEL// /_}-$SERIAL"
  mkdir -p "$DDIR"
  ok "Pannello: $MODEL (seriale $SERIAL) — dati in $DDIR"
}

# ---------- root ----------
ROOTMODE=""
detect_root() {
  if [[ "$(A shell id -u | clean)" == "0" ]]; then ROOTMODE=adb
  elif A root 2>/dev/null | grep -qiE 'restarting|already'; then
    sleep 3; adb connect "$DEV" >/dev/null; A wait-for-device
    [[ "$(A shell id -u | clean)" == "0" ]] && ROOTMODE=adb
  fi
  if [[ -z "$ROOTMODE" ]]; then
    if [[ "$(A shell "su -c 'id -u'" 2>/dev/null | clean)" == "0" ]]; then ROOTMODE=su
    elif [[ "$(A shell "su 0 id -u" 2>/dev/null | clean)" == "0" ]]; then ROOTMODE=su0
    fi
  fi
  [[ -n "$ROOTMODE" ]] || die "nessun accesso root (né 'adb root' né 'su'). Hai accettato l'accordo ADB nell'app eWeLink?"
  ok "Accesso root: $ROOTMODE"
}

# Esegue uno script come root sul pannello (via file, per evitare problemi di quoting)
R() {
  local f; f=$(mktemp)
  printf '%s\n' "$1" > "$f"
  A push "$f" "$TMP/gda-run.sh" >/dev/null
  rm -f "$f"
  case "$ROOTMODE" in
    adb) A shell "sh $TMP/gda-run.sh" | clean ;;
    su)  A shell "su -c 'sh $TMP/gda-run.sh'" | clean ;;
    su0) A shell "su 0 sh $TMP/gda-run.sh" | clean ;;
  esac
}

# Comando root con uscita binaria (per dd)
RAW() {
  case "$ROOTMODE" in
    adb) A exec-out "$1" ;;
    su)  A exec-out "su -c '$1'" ;;
    su0) A exec-out "su 0 $1" ;;
  esac
}

find_byname() {
  BYNAME=$(R 'for d in /dev/block/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do [ -d "$d" ] && { echo "$d"; break; }; done')
  [[ -n "$BYNAME" ]] || die "cartella delle partizioni (by-name) non trovata"
}

verity_active() {
  local src; src=$(R 'mount | grep -E " /system | / " | head -n1 | cut -d" " -f1')
  [[ "$src" == /dev/block/dm-* ]]
}

# ---------- check ----------
cmd_check() {
  connect; detect_root; find_byname
  say "Sistema"
  echo "   Android $(A shell getprop ro.build.version.release | clean) — build $(A shell getprop ro.build.type | clean) — $(A shell getprop ro.build.display.id | clean)"
  echo "   ABI: $(A shell getprop ro.product.cpu.abilist | clean)   SELinux: $(R 'getenforce 2>/dev/null || echo ?')"
  echo "   Partizioni ($BYNAME): $(R "ls $BYNAME" | tr '\n' ' ')"
  R 'df /system 2>/dev/null | tail -n1' | sed 's/^/   \/system: /'
  if verity_active; then
    warn "dm-verity ATTIVO su /system: va disattivato prima dell'installazione"
    [[ "$ROOTMODE" == adb ]] && warn "prova: adb -s $DEV disable-verity && adb -s $DEV reboot" \
      || warn "con questo tipo di root non è disattivabile automaticamente: installazione NON sicura"
  else ok "dm-verity non attivo: /system è modificabile"; fi

  say "Analisi app installate"
  local home; home=$(A shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | clean | tail -n1 | cut -d/ -f1 || true)
  [[ -n "$home" ]] && ok "Launcher attuale: $home"
  local out="$DDIR/packages-to-remove.txt"
  {
    echo "# GDAHome OS — app che verranno RIMOSSE da $MODEL"
    echo "# Righe senza # = rimosse.  Aggiungi/togli il # per scegliere."
    echo "# Formato: <pacchetto> <percorso apk>"
  } > "$out"
  local n=0
  while IFS= read -r line; do
    line="${line#package:}"; local pkg="${line##*=}" path="${line%=*}"
    local lpkg; lpkg=$(printf '%s' "$pkg" | tr '[:upper:]' '[:lower:]')
    [[ "$pkg" =~ $KEEP_RE ]] && continue
    if [[ "$lpkg" =~ $SONOFF_RE ]] || [[ "$pkg" == "$home" && "$pkg" != "$PKG" ]]; then
      echo "$pkg $path" >> "$out"; n=$((n+1)); echo "   ${c_red}rimuovi${c_off} $pkg  ($path)"
    elif [[ ! "$pkg" =~ ^com\.(google|rockchip|qualcomm|android)\. ]]; then
      echo "#$pkg $path" >> "$out"; echo "   ${c_yel}verifica${c_off} $pkg  ($path)"
    fi
  done < <(A shell pm list packages -f | clean | sort -t= -k2)
  ok "$n app Sonoff selezionate. Elenco modificabile: $out"
  echo
  echo "Controlla l'elenco, poi esegui:  $0 backup $IP"
}

# ---------- backup ----------
cmd_backup() {
  local with_userdata=0
  [[ "${1:-}" == "--with-userdata" ]] && with_userdata=1
  connect; detect_root; find_byname
  local bdir="$DDIR/backup-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$bdir"
  say "Backup in $bdir"
  A shell getprop | clean > "$bdir/getprop.txt"
  A shell pm list packages -f | clean > "$bdir/packages.txt"
  R 'cat /proc/partitions; echo; mount' > "$bdir/partitions-mount.txt"
  R "ls -l $BYNAME" > "$bdir/by-name.txt"

  for p in $(R "ls $BYNAME"); do
    if [[ $with_userdata -eq 0 && "$p" =~ ^(userdata|cache)$ ]]; then warn "salto $p (usa --with-userdata per includerla)"; continue; fi
    local blk="$BYNAME/$p" size dsum lsum
    size=$(R "blockdev --getsize64 \$(readlink -f $blk)")
    printf "   %-14s %8s MB ... " "$p" "$((size/1048576))"
    RAW "dd if=$blk bs=4194304 2>/dev/null" > "$bdir/$p.img"
    local got; got=$(stat -c %s "$bdir/$p.img" 2>/dev/null || stat -f %z "$bdir/$p.img")
    [[ "$got" == "$size" ]] || { echo; die "$p: dimensione $got diversa da $size. Backup NON valido."; }
    dsum=$(R "md5sum $blk | cut -d' ' -f1")
    lsum=$( (md5sum "$bdir/$p.img" 2>/dev/null || md5 -r "$bdir/$p.img") | cut -d' ' -f1)
    [[ "$dsum" == "$lsum" ]] || { echo; die "$p: checksum diverso (pannello $dsum, PC $lsum). Backup NON valido."; }
    echo "${c_grn}ok${c_off} $lsum"
    echo "$lsum  $p.img" >> "$bdir/MD5SUMS"
  done
  echo "$bdir" > "$DDIR/LAST_BACKUP"
  ok "Backup completo e verificato. Conservalo: è l'unico modo per tornare al firmware originale."
  [[ $with_userdata -eq 1 ]] && warn "userdata contiene password Wi-Fi e dati personali: tienilo al sicuro"
  echo; echo "Ora puoi eseguire:  $0 install $IP"
}

# ---------- install ----------
need_file() { [[ -f "$HERE/$1" ]] || die "manca $1 nella cartella $HERE"; }

cmd_install() {
  need_file GDAHome.apk; need_file bootanimation.zip
  connect; detect_root
  [[ -f "$DDIR/LAST_BACKUP" && -f "$(cat "$DDIR/LAST_BACKUP")/MD5SUMS" ]] || die "nessun backup valido per questo pannello: esegui prima '$0 backup $IP'"
  local list="$DDIR/packages-to-remove.txt"
  [[ -f "$list" ]] || die "esegui prima '$0 check $IP'"
  if verity_active; then die "dm-verity è attivo: modificare /system impedirebbe l'avvio. Vedi '$0 check $IP'."; fi

  local abi; abi=$(A shell getprop ro.product.cpu.abi | clean)
  local serial_bin="gdaserial-$abi"
  [[ -f "$HERE/$serial_bin" ]] || serial_bin="gdaserial-armeabi-v7a"
  [[ -f "$HERE/$serial_bin" ]] || { warn "binario Zigbee non trovato: il ponte Zigbee userà la copia dentro l'app"; serial_bin=""; }

  ITEMS=()
  while IFS= read -r l; do ITEMS+=("$l"); done < <(grep -vE '^[[:space:]]*(#|$)' "$list" | clean)
  [[ ${#ITEMS[@]} -gt 0 ]] || die "l'elenco $list non contiene app da rimuovere"
  say "Verranno rimosse ${#ITEMS[@]} app:"
  for it in "${ITEMS[@]}"; do
    local pkg="${it%% *}"
    [[ "$pkg" =~ $KEEP_RE ]] && die "$pkg è un componente Android di base: toglilo dall'elenco"
    echo "   - $it"
  done
  echo
  echo "${c_yel}Il pannello verrà modificato in modo permanente (ripristinabile con 'restore' o col backup).${c_off}"
  read -r -p "Scrivi GDAHOME per continuare: " ans
  [[ "$ans" == "GDAHOME" ]] || die "annullato"

  local bdir; bdir=$(cat "$DDIR/LAST_BACKUP")
  mkdir -p "$bdir/apk"

  say "Copia file sul pannello"
  A push "$HERE/GDAHome.apk" "$TMP/GDAHome.apk" >/dev/null
  A push "$HERE/bootanimation.zip" "$TMP/bootanimation.zip" >/dev/null
  [[ -n "$serial_bin" ]] && A push "$HERE/$serial_bin" "$TMP/gdaserial" >/dev/null
  ok "fatto"

  # App installate in /data: si salvano sul PC e si disinstallano normalmente
  local sys_items=("")   # primo elemento vuoto: evita errori con bash vecchi e array vuoti
  for it in "${ITEMS[@]}"; do
    local pkg="${it%% *}" path="${it#* }"
    if [[ "$path" == /data/* ]]; then
      A pull "$path" "$bdir/apk/$pkg.apk" >/dev/null && ok "salvato $pkg.apk"
      A shell pm uninstall "$pkg" | clean | sed "s/^/   $pkg: /"
    else
      sys_items+=("$pkg $path")
    fi
  done

  say "Modifica della partizione di sistema"
  local script='set -e
mount -o rw,remount /system 2>/dev/null || mount -o rw,remount / 2>/dev/null
touch /system/.gdatest && rm /system/.gdatest || { echo "ERRORE: /system non scrivibile"; exit 1; }
R='"$REMOVED"'
mkdir -p $R
[ -f $R/build.prop.orig ] || cp -p /system/build.prop $R/build.prop.orig
'
  for it in "${sys_items[@]}"; do
    [[ -n "$it" ]] || continue
    local pkg="${it%% *}" path="${it#* }"
    script+="
p='$path'; d=\$(dirname \"\$p\"); b=\$(basename \"\$d\")
if [ -e \"\$p\" ] && ! grep -q '^$pkg|' \$R/manifest.txt 2>/dev/null; then
  if [ \"\$b\" = app ] || [ \"\$b\" = priv-app ]; then src=\"\$p\"; else src=\"\$d\"; fi
  mv \"\$src\" \$R/$pkg.removed && echo \"$pkg|\$src\" >> \$R/manifest.txt && echo \"   rimossa $pkg\"
fi"
  done
  script+='
# GDAHome come app di sistema
mkdir -p /system/app/GDAHome
cp '"$TMP"'/GDAHome.apk /system/app/GDAHome/GDAHome.apk
chmod 755 /system/app/GDAHome; chmod 644 /system/app/GDAHome/GDAHome.apk
chcon -R u:object_r:system_file:s0 /system/app/GDAHome 2>/dev/null || true
echo "   GDAHome installata in /system/app"
# Ponte Zigbee
if [ -f '"$TMP"'/gdaserial ]; then
  cp '"$TMP"'/gdaserial /system/bin/gdaserial; chmod 755 /system/bin/gdaserial
  chcon u:object_r:system_file:s0 /system/bin/gdaserial 2>/dev/null || true
  echo "   gdaserial installato in /system/bin"
fi
# Animazione di avvio
if [ -f /system/media/bootanimation.zip ] && [ ! -f $R/bootanimation.zip.orig ]; then mv /system/media/bootanimation.zip $R/bootanimation.zip.orig; fi
mkdir -p /system/media
cp '"$TMP"'/bootanimation.zip /system/media/bootanimation.zip; chmod 644 /system/media/bootanimation.zip
chcon u:object_r:system_file:s0 /system/media/bootanimation.zip 2>/dev/null || true
echo "   animazione di avvio GDAHome"
# Identità del sistema + ADB via rete sempre attivo (senza app Sonoff nessuno lo riattiverebbe)
grep -q "^ro.gdahome.os=" /system/build.prop || printf "\n# GDAHome OS\nro.gdahome.os=1\npersist.sys.gdahome=1\nservice.adb.tcp.port=5555\n" >> /system/build.prop
setprop persist.adb.tcp.port 5555
settings put global adb_enabled 1 2>/dev/null || true
sync
mount -o ro,remount /system 2>/dev/null || mount -o ro,remount / 2>/dev/null || true
rm -f '"$TMP"'/GDAHome.apk '"$TMP"'/bootanimation.zip '"$TMP"'/gdaserial
echo GDA_OK'
  local out; out=$(R "$script")
  echo "$out" | grep -v GDA_OK || true
  echo "$out" | grep -q GDA_OK || die "modifica di sistema non completata: nulla è stato riavviato. Controlla l'output sopra."
  R "cat $REMOVED/manifest.txt 2>/dev/null" > "$bdir/removed-manifest.txt" || true

  say "Impostazioni di sistema"
  A shell settings put system screen_off_timeout 2147483647
  A shell settings put global stay_on_while_plugged_in 7 || true
  A shell settings put global policy_control immersive.full=* || true
  A shell settings put global device_name GDAHome || true
  ok "schermo sempre gestito da GDAHome, barre di sistema nascoste"

  say "Riavvio in GDAHome OS"
  A reboot || true
  sleep 20
  adb connect "$DEV" >/dev/null 2>&1 || true
  A wait-for-device
  until [[ "$(A shell getprop sys.boot_completed | clean)" == "1" ]]; do sleep 2; done
  A shell cmd package set-home-activity "$HOME_ACT" >/dev/null 2>&1 || true
  A shell appops set "$PKG" WRITE_SETTINGS allow >/dev/null 2>&1 || true
  A shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
  A shell am start -n "$HOME_ACT" >/dev/null
  if A shell pm path "$PKG" | grep -q /system/app/GDAHome; then ok "GDAHome OS attivo"; else warn "GDAHome non risulta app di sistema: controlla con 'pm path $PKG'"; fi
  echo
  echo "Pannello pronto. Tieni premuto il logo per le impostazioni (PIN 1234)."
}

# ---------- restore ----------
cmd_restore() {
  connect; detect_root
  say "Ripristino del software Sonoff"
  local out; out=$(R 'set -e
mount -o rw,remount /system 2>/dev/null || mount -o rw,remount / 2>/dev/null
R='"$REMOVED"'
[ -d $R ] || { echo "nessuna modifica GDAHome OS trovata"; echo GDA_OK; exit 0; }
if [ -f $R/manifest.txt ]; then
  while IFS="|" read -r pkg src; do
    [ -e $R/$pkg.removed ] && mv $R/$pkg.removed "$src" && echo "   ripristinata $pkg"
  done < $R/manifest.txt
fi
[ -f $R/bootanimation.zip.orig ] && mv $R/bootanimation.zip.orig /system/media/bootanimation.zip || rm -f /system/media/bootanimation.zip
[ -f $R/build.prop.orig ] && cp -p $R/build.prop.orig /system/build.prop
rm -rf /system/app/GDAHome /system/bin/gdaserial
rm -rf $R
sync; mount -o ro,remount /system 2>/dev/null || mount -o ro,remount / 2>/dev/null || true
echo GDA_OK')
  echo "$out" | grep -v GDA_OK || true
  echo "$out" | grep -q GDA_OK || die "ripristino non completato"
  if [[ -f "$DDIR/LAST_BACKUP" && -d "$(cat "$DDIR/LAST_BACKUP")/apk" ]]; then
    for apk in "$(cat "$DDIR/LAST_BACKUP")"/apk/*.apk; do [[ -f "$apk" ]] && A install -r "$apk" | clean | sed "s/^/   $(basename "$apk"): /"; done
  fi
  A shell settings put system screen_off_timeout 60000
  A shell settings delete global policy_control >/dev/null 2>&1 || true
  ok "fatto, riavvio"
  A reboot || true
}

# ---------- flash (emergenza) ----------
cmd_flash() {
  local part="${1:?partizione}" img="${2:?file .img}"
  [[ -f "$img" ]] || die "$img non trovato"
  connect; detect_root; find_byname
  local blk="$BYNAME/$part"
  [[ -n "$(R "[ -e $blk ] && echo y")" ]] || die "partizione $part inesistente"
  local size; size=$(R "blockdev --getsize64 \$(readlink -f $blk)")
  local isz; isz=$(stat -c %s "$img" 2>/dev/null || stat -f %z "$img")
  [[ "$isz" -le "$size" ]] || die "immagine più grande della partizione"
  echo "${c_red}Stai per riscrivere $part ($blk) con $img.${c_off}"
  read -r -p "Scrivi FLASH $part per continuare: " ans
  [[ "$ans" == "FLASH $part" ]] || die "annullato"
  say "Copia immagine"
  A push "$img" "$TMP/flash.img"
  R "dd if=$TMP/flash.img of=$blk bs=4194304 && sync && rm $TMP/flash.img && echo scritto"
  ok "fatto. Riavvia con: adb -s $DEV reboot"
}

case "$CMD" in
  check)   cmd_check ;;
  backup)  cmd_backup "$@" ;;
  install) cmd_install ;;
  restore) cmd_restore ;;
  flash)   cmd_flash "$@" ;;
  *) usage ;;
esac
