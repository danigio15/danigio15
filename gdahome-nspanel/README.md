# GDAHome per Sonoff NSPanel Pro e NSPanel Pro 120

GDAHome è un'interfaccia per pannelli a parete che **sostituisce la UI Sonoff/eWeLink** sui
pannelli NSPanel Pro (86, 4") e NSPanel Pro 120. Gira direttamente sul pannello come launcher
Android e diventa la schermata principale all'accensione.

## Cosa fa

| Funzione | Come |
|---|---|
| Schermata principale | Orologio, data, temperatura e riquadri dei dispositivi. Si adatta da sola al 4" quadrato e al 120 verticale |
| Home Assistant | Collegamento WebSocket in tempo reale. Gestisce luci (luminosità con pressione lunga), prese, tapparelle, termostati, scene, script, serrature e sensori |
| Relè del pannello | Fino a 2 relè via GPIO/sysfs, dal pannello o da Home Assistant |
| MQTT Discovery | Relè, schermo, presenza, temperatura e luce compaiono da soli in Home Assistant |
| Salvaschermo | Orologio attenuato dopo 60 s, nero dopo 5 min. Si risveglia al tocco, alla prossimità o da HA |
| Analisi hardware | Elenca GPIO, retroilluminazione, sensori, porte seriali e app Sonoff del tuo pannello |
| Protezione | Impostazioni protette da PIN (tieni premuto il logo) |

## 1. Scaricare l'APK

Ogni push su questo repository compila l'app con GitHub Actions:
**Actions → GDAHome APK → ultima esecuzione → Artifacts → `GDAHome`**.
Lo zip contiene `GDAHome.apk` e gli script di installazione.

## 2. Abilitare ADB sul pannello

1. Associa il pannello all'app eWeLink, se non l'hai già fatto.
2. Nell'app eWeLink apri la pagina del pannello e **tocca più volte
   l'ID dispositivo** finché si attiva la modalità sviluppatore. Poi attiva ADB.
3. ⚠️ Sonoff avvisa che accettare l'accordo ADB è **irreversibile**: il pannello resta sbloccato
   e non riceve più aggiornamenti firmware ufficiali.
4. Annota l'IP del pannello (dal router o dall'app eWeLink).

## 3. Installare

Ti serve [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`).

```bash
# Fase 1: installa GDAHome lasciando le app Sonoff (consigliato per qualche giorno di prova)
./install.sh 192.168.1.50 GDAHome.apk

# Fase 2: quando tutto funziona, disattiva completamente l'interfaccia Sonoff
./install.sh 192.168.1.50 GDAHome.apk --disable-sonoff
```

Su Windows puoi usare `install.bat 192.168.1.50` per la fase 1. Per la fase 2 usa Git Bash o WSL.

Le app Sonoff vengono **disattivate, non cancellate**. Per tornare al firmware originale:
`./restore.sh 192.168.1.50` (aggiungi `--uninstall-gdahome` per rimuovere anche GDAHome).

## 4. Configurare

Tieni premuto il logo in alto a sinistra e inserisci il PIN (predefinito **1234**, cambialo subito).

- **Home Assistant**: indirizzo (es. `http://192.168.1.10:8123`) e un token a lunga durata
  (Profilo → Sicurezza). Entità: una per riga, con un nome facoltativo:
  ```
  light.soggiorno|Soggiorno
  switch.presa_tv
  cover.tapparella_cucina|Tapparella
  climate.termostato
  scene.buonanotte|Buonanotte
  sensor.temperatura_esterna
  ```
- **MQTT**: broker (es. il componente Mosquitto di HA). Il pannello appare in HA come
  dispositivo "GDAHome", con relè, schermo, presenza e temperatura.
- **Relè**: vedi il paragrafo sotto.

### Topic MQTT

| Topic | Payload |
|---|---|
| `gdahome/<id>/relay1/set`, `relay2/set` | `ON` / `OFF` |
| `gdahome/<id>/screen/set` | `ON` (risveglia) / `OFF` (schermo nero) |
| `gdahome/<id>/command` | `reload`, `probe` |
| `gdahome/<id>/probe` | rapporto dell'analisi hardware (in uscita) |
| `gdahome/<id>/status` | `online` / `offline` |

## 5. Relè, retroilluminazione e sensori: l'analisi hardware

I percorsi hardware (GPIO dei relè, retroilluminazione, sensore di temperatura) **non sono
documentati da Sonoff** e possono cambiare tra NSPanel Pro, Pro 120 e versioni del firmware.
Per questo GDAHome non li indovina: li trovi con lo strumento incluso.

1. Impostazioni → **Analisi hardware**. Il rapporto viene anche salvato in
   `/sdcard/Android/data/it.gdahome.panel/files/gdahome_probe.txt` e pubblicato su
   MQTT in `gdahome/<id>/probe`.
2. Recupera il file: `adb pull /sdcard/Android/data/it.gdahome.panel/files/gdahome_probe.txt`
3. Cerca i GPIO configurati come `out`. Per individuare il relè, prova a cambiarne uno mentre
   ascolti il clic:
   ```bash
   adb shell su -c 'echo 1 > /sys/class/gpio/gpioNN/value'
   adb shell su -c 'echo 0 > /sys/class/gpio/gpioNN/value'
   ```
4. Inserisci il numero GPIO (o il percorso completo del file) in Impostazioni → Relè 1 / Relè 2.

Fai la stessa cosa per la retroilluminazione (`/sys/class/backlight/...`) e per la temperatura
(`/sys/class/thermal/...`, `/sys/bus/iio/...`, `/sys/class/hwmon/...`).

⚠️ Su alcune versioni del firmware i relè e lo Zigbee sono gestiti dalle app Sonoff. Prima di
disattivarle con `--disable-sonoff`, verifica che i relè funzionino da GDAHome.

## Note importanti

- **Lo schermo non viene mai spento da Android**: sul firmware AOSP del NSPanel Pro, senza l'app
  Sonoff, uno schermo spento non si riaccende (si dovrebbe staccare l'alimentazione). GDAHome
  simula lo spegnimento con schermo nero e luminosità minima, e si risveglia al tocco.
- Lo **Zigbee** integrato non è gestito da GDAHome. Se ti serve come coordinatore per
  Zigbee2MQTT/ZHA, puoi usare un progetto dedicato (es. NSPanel Pro Tools) insieme a GDAHome.
- Per uscire dalla modalità pannello: Impostazioni → *Cambia launcher predefinito*.

## Sviluppo

App Android nativa in Kotlin, minSdk 26 (i pannelli usano Android 8.1).

```
app/src/main/java/it/gdahome/panel/
├── PanelHub.kt        stato centrale: relè, sensori, HA, MQTT
├── GdaService.kt      servizio in primo piano (funziona anche a schermo nero)
├── Config.kt          impostazioni
├── ha/                client WebSocket Home Assistant
├── mqtt/              ponte MQTT + Discovery
├── hw/                GPIO/sysfs, retroilluminazione, sensori Android
└── ui/                launcher, riquadri, impostazioni, analisi hardware
```

Compilazione locale (richiede Android SDK): `./gradlew assembleRelease`.
L'APK viene firmato con `keystore/gdahome-sideload.jks` per poter aggiornare l'app senza
disinstallarla. È una chiave pubblica per uso personale: per distribuire l'app crea la tua.
