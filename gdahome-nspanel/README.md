# GDAHome OS per Sonoff NSPanel Pro e NSPanel Pro 120

GDAHome OS trasforma un NSPanel Pro (86, 4") o un NSPanel Pro 120 in un pannello GDAHome.
**Tutto il software Sonoff/eWeLink viene rimosso dal sistema**: niente interfaccia Sonoff,
niente cloud eWeLink, niente aggiornamenti Sonoff. Il pannello si avvia con l'animazione
GDAHome e mostra soltanto GDAHome.

## Com'è fatto

```
┌──────────────────────────────────────────────────────────┐
│  GDAHome (app di sistema in /system/app)                 │
│  schermata, Home Assistant, relè, sensori, MQTT, Zigbee  │
├──────────────────────────────────────────────────────────┤
│  gdaserial (/system/bin)  ponte Zigbee → rete            │
│  bootanimation GDAHome, build.prop GDAHome OS            │
├──────────────────────────────────────────────────────────┤
│  Android 8.1 di base (AOSP), senza app Sonoff/eWeLink    │
├──────────────────────────────────────────────────────────┤
│  Kernel e driver originali: display, touch, Wi-Fi, GPIO  │
└──────────────────────────────────────────────────────────┘
```

Il kernel e i driver originali restano perché sono gli unici che conoscono l'hardware del
pannello (schermo, touch, Wi-Fi, relè, chip Zigbee). Tutto quello che sta sopra è GDAHome.

### Perché non un Linux scritto da zero?

Un sistema operativo completamente nuovo, kernel compreso, ha bisogno del *device tree* e dei
driver specifici del pannello: inizializzazione del display, controller touch, GPIO dei relè,
alimentazione. Sonoff non li pubblica. Flashare un sistema senza averli renderebbe il pannello
inutilizzabile. Il comando `backup` copia sul PC tutte le partizioni, comprese `boot` e `dtb`:
è proprio il materiale da cui estrarre il device tree per un futuro **GDAHome OS Linux nativo**
(vedi [Roadmap](#roadmap)).

## Cosa fa GDAHome

| Funzione | Come |
|---|---|
| Schermata principale | Orologio, data, temperatura e riquadri dei dispositivi. Si adatta da sola al 4" e al 120 |
| Home Assistant | Collegamento WebSocket in tempo reale. Gestisce luci (luminosità con pressione lunga), prese, tapparelle, termostati, scene, script, serrature e sensori |
| Relè del pannello | Fino a 2 relè via GPIO/sysfs, dal pannello o da Home Assistant |
| Zigbee integrato | Il chip Zigbee del pannello diventa un coordinatore di rete per Zigbee2MQTT/ZHA (`socket://IP:6638`) |
| MQTT Discovery | Relè, schermo, presenza, temperatura e luce compaiono da soli in Home Assistant |
| Salvaschermo | Orologio attenuato, poi nero. Si risveglia al tocco, alla prossimità o da HA |
| Analisi hardware | Elenca GPIO, retroilluminazione, sensori, porte seriali e app Sonoff del pannello |
| Protezione | Impostazioni protette da PIN (tieni premuto il logo) |

## Scaricare

Ogni push su questo repository compila tutto con GitHub Actions:
**Actions → GDAHome OS → ultima esecuzione → Artifacts → `GDAHome-OS`**.

Lo zip contiene:

| File | Cosa è |
|---|---|
| `gdahome-os.sh` | lo strumento di installazione di GDAHome OS |
| `GDAHome.apk` | l'interfaccia GDAHome |
| `gdaserial-armeabi-v7a`, `gdaserial-arm64-v8a` | il ponte Zigbee nativo |
| `bootanimation.zip` | l'animazione di avvio |
| `install.sh`, `install.bat`, `restore.sh` | modalità leggera: solo app, senza modificare il sistema |

## Installare GDAHome OS

Ti servono un PC con [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
(`adb`) e bash: Linux, macOS, oppure Git Bash o WSL su Windows. Ripeti la procedura per ogni pannello.

### 0. Abilitare ADB

1. Associa il pannello all'app eWeLink, se non l'hai già fatto.
2. Nell'app eWeLink apri la pagina del pannello e **tocca più volte l'ID dispositivo** finché
   si attiva la modalità sviluppatore. Poi attiva ADB.
3. ⚠️ Sonoff avvisa che accettare l'accordo ADB è **irreversibile** e che il pannello non
   riceverà più aggiornamenti ufficiali.
4. Annota l'IP del pannello (dal router o dall'app eWeLink).

### 1. Verifica

```bash
chmod +x gdahome-os.sh
./gdahome-os.sh check 192.168.1.50
```

Il comando controlla:
- l'accesso root;
- che `/system` sia modificabile (dm-verity);
- le partizioni del pannello.

Prepara anche l'elenco delle app da rimuovere in
`gdahome-os-data/<modello>-<seriale>/packages-to-remove.txt`:
- le app Sonoff/eWeLink e il launcher Sonoff sono già selezionate;
- le app non riconosciute sono segnate con `#` perché tu le verifichi.

Aprilo e controllalo.

### 2. Backup (obbligatorio)

```bash
./gdahome-os.sh backup 192.168.1.50
```

Copia **ogni partizione** del pannello sul PC e la verifica con il checksum MD5. Restano
escluse `userdata` e `cache`: aggiungi `--with-userdata` per includerle.
Senza un backup valido l'installazione non parte. **Conservalo**: è l'unico modo per tornare
al firmware originale.

### 3. Installazione

```bash
./gdahome-os.sh install 192.168.1.50
```

Mostra cosa verrà rimosso e chiede di scrivere `GDAHOME` per confermare. Poi:

1. sposta le app Sonoff fuori dal sistema, in `/system/gdahome-removed`. Non vengono più
   caricate, ma si possono ripristinare;
2. installa GDAHome come app di sistema, `gdaserial` in `/system/bin` e l'animazione di avvio;
3. tiene **sempre attivo ADB sulla porta 5555**, perché senza le app Sonoff nessuno lo
   riattiverebbe;
4. nasconde le barre di sistema e affida lo schermo a GDAHome;
5. riavvia il pannello in GDAHome OS.

### 4. Configurare

Tieni premuto il logo in alto a sinistra e inserisci il PIN (predefinito **1234**, cambialo subito).

- **Home Assistant**: indirizzo (es. `http://192.168.1.10:8123`) e un token a lunga durata
  (Profilo → Sicurezza). Entità: una per riga, con un nome facoltativo:
  ```
  light.soggiorno|Soggiorno
  switch.presa_tv
  cover.tapparella_cucina|Tapparella
  climate.termostato
  scene.buonanotte|Buonanotte
  ```
- **MQTT**: broker (es. il componente Mosquitto di HA). Il pannello compare in HA come
  dispositivo "GDAHome".
- **Relè, Zigbee, retroilluminazione**: vedi [Analisi hardware](#analisi-hardware).

### Tornare a Sonoff

```bash
./gdahome-os.sh restore 192.168.1.50
```

Rimette le app Sonoff, l'animazione e il `build.prop` originali, rimuove GDAHome dal sistema
e riavvia.

**Emergenza**: `./gdahome-os.sh flash 192.168.1.50 system gdahome-os-data/.../backup-.../system.img`
riscrive una partizione dal backup. Se il pannello non si avvia più e ADB non risponde, le
immagini del backup si possono riscrivere in modalità Rockchip loader con `rkdeveloptool`.
Serve però l'accesso USB alla scheda.

## Analisi hardware

Sonoff non documenta i percorsi hardware, che possono cambiare tra NSPanel Pro, Pro 120 e
versioni del firmware. GDAHome non li indovina: li trovi con lo strumento incluso.

1. Impostazioni → **Analisi hardware**. Il rapporto viene salvato in
   `/sdcard/Android/data/it.gdahome.panel/files/gdahome_probe.txt` e pubblicato su MQTT in
   `gdahome/<id>/probe`. Per scaricarlo:
   `adb pull /sdcard/Android/data/it.gdahome.panel/files/gdahome_probe.txt`
2. **Relè**: cerca i GPIO configurati come `out` e provali uno alla volta, ascoltando il clic:
   ```bash
   adb shell su -c 'echo 1 > /sys/class/gpio/gpioNN/value'
   adb shell su -c 'echo 0 > /sys/class/gpio/gpioNN/value'
   ```
   Poi inserisci il numero in Impostazioni → Relè 1 / Relè 2.
3. **Zigbee**: tra le porte seriali (`/dev/ttyS*`) c'è quella del chip Zigbee. Inseriscila in
   Impostazioni → Zigbee integrato, attiva "Esponi Zigbee in rete" e configura Zigbee2MQTT:
   ```yaml
   serial:
     port: tcp://192.168.1.50:6638
     adapter: ember   # chip Silicon Labs
   ```
   In ZHA scegli "inserimento manuale" con `socket://192.168.1.50:6638`. Velocità e controllo
   di flusso dipendono dal firmware del chip (di solito 115200).
4. **Retroilluminazione / temperatura**: `/sys/class/backlight/...`,
   `/sys/class/thermal/...`, `/sys/bus/iio/...` o `/sys/class/hwmon/...`.

## Modalità leggera (senza modificare il sistema)

Per provare GDAHome prima di installare l'OS, si può installare soltanto l'app:

```bash
./install.sh 192.168.1.50 GDAHome.apk                   # app + launcher, Sonoff resta
./install.sh 192.168.1.50 GDAHome.apk --disable-sonoff  # disattiva (non rimuove) le app Sonoff
./restore.sh 192.168.1.50                               # annulla
```

## Topic MQTT

| Topic | Payload |
|---|---|
| `gdahome/<id>/relay1/set`, `relay2/set` | `ON` / `OFF` |
| `gdahome/<id>/screen/set` | `ON` (risveglia) / `OFF` (schermo nero) |
| `gdahome/<id>/command` | `reload`, `probe` |
| `gdahome/<id>/probe` | rapporto dell'analisi hardware (in uscita) |
| `gdahome/<id>/status` | `online` / `offline` |

## Note importanti

- **Lo schermo non viene mai spento da Android**: su questo firmware, senza l'app Sonoff, uno
  schermo spento non si riaccende. GDAHome simula lo spegnimento con schermo nero e
  luminosità minima.
- **ADB resta aperto in rete** (porta 5555, con root): chiunque sia sulla tua rete locale può
  collegarsi al pannello. Tieni i pannelli su una rete fidata o su una VLAN separata.
- Prima di rimuovere le app Sonoff, controlla con l'Analisi hardware che relè e Zigbee
  funzionino da GDAHome.
- GDAHome OS è stato provato in simulazione, non ancora su un pannello reale: segui i passi
  in ordine e non saltare il backup.

## Roadmap

1. ✅ GDAHome OS su base Android: rimozione Sonoff, app di sistema, Zigbee, boot animation.
2. ⏳ Profili hardware pronti per NSPanel Pro 86 e Pro 120, ricavati dai rapporti di Analisi
   hardware: relè, retroilluminazione e Zigbee configurati in automatico.
3. 🔭 GDAHome OS Linux nativo: kernel e device tree estratti dal backup (`boot.img`/`dtb`),
   sistema minimo Buildroot e interfaccia GDAHome nativa, flash via `rkdeveloptool`.

## Sviluppo

```
gdahome-nspanel/
├── app/       interfaccia GDAHome (Kotlin, Android 8.1+)
│   └── src/main/java/it/gdahome/panel/
│       ├── PanelHub.kt     stato centrale: relè, sensori, HA, MQTT, Zigbee
│       ├── GdaService.kt   servizio in primo piano
│       ├── ha/             client WebSocket Home Assistant
│       ├── mqtt/           ponte MQTT + Discovery
│       ├── hw/             GPIO/sysfs, retroilluminazione, sensori, ponte Zigbee
│       └── ui/             launcher, riquadri, impostazioni, analisi hardware
├── native/    gdaserial.c  ponte UART ↔ TCP (C, NDK)
├── os/        gdahome-os.sh, generatore dell'animazione di avvio
└── tools/     installazione leggera via ADB
```

L'APK viene firmato con `keystore/gdahome-sideload.jks` per poterlo aggiornare senza
disinstallarlo. È una chiave pubblica per uso personale: per distribuire l'app crea la tua.
