/* La casa finta per i render della console: «Casa Rossi», Premium, due
 * telefoni e i pannelli a muro. */
const ORA = Date.now();
const fa = (min) => new Date(ORA - min * 60000).toISOString();

export const DISPOSITIVI = [
  { id: "dm_a1b2c3d4e5f60718", nome: "iPhone di Marta", sistema: "ios", marca: "Apple", modello: "iPhone 15", app: "1.19.32", utente: "a".repeat(32), natoIl: fa(60 * 24 * 40), vistoIl: fa(2), collegati: 1 },
  { id: "dm_b1b2c3d4e5f60719", nome: "Pixel di Luca", sistema: "android", marca: "Google", modello: "Pixel 8", app: "1.19.32", utente: "b".repeat(32), natoIl: fa(60 * 24 * 12), vistoIl: fa(30), collegati: 0 },
];

export const PANNELLI = [
  { id: "pm_ingresso01", nome: "Ingresso", indirizzo: "192.168.1.51", modello: "86", stato: "pronto", acceso: true, os: "2.0.0", app: "1.19.33", plancia: "Ingresso", utente: "a".repeat(32), vistoIl: fa(0) },
  { id: "pm_cucina0002", nome: "Cucina", indirizzo: "192.168.1.52", modello: "120", stato: "pronto", acceso: true, os: "2.0.0", app: "1.19.32", aggiornabile: true, plancia: "Cucina", utente: "a".repeat(32), vistoIl: fa(1) },
  { id: "pm_camera0003", nome: "Camera", indirizzo: "192.168.1.54", modello: "86", stato: "pronto", acceso: false, os: "2.0.0", app: "1.19.33", plancia: "Camera", utente: "b".repeat(32), vistoIl: fa(180) },
  { id: "pm_nuovo00004", nome: "", indirizzo: "192.168.1.53", modello: "86", stato: "sonoff", vistoIl: fa(0) },
];

/* Un lavoro a meta': l'installazione di un pannello nuovo. */
export const PASSI = (fino) => {
  const tutti = [
    ["Verifica", "NSPanel Pro 86 · Android 8.1 · root ok"],
    ["Copia di sicurezza", "14 partizioni · 1,9 GB in /share/gdahome-os/ingresso-2"],
    ["Installazione", "GdAHome OS, app GdAHome Muro, animazione d'avvio"],
    ["Abbinamento", "con la casa, per Marta · plancia «Soggiorno»"],
    ["Riavvio", "il pannello si accende con GdAHome"],
  ];
  return tutti.map(([nome, dettaglio], i) => ({
    nome,
    dettaglio,
    stato: i < fino ? "fatto" : i === fino ? "adesso" : "dopo",
    ...(i === fino && i === 1 ? { percento: 62 } : {}),
  }));
};

const SCENARIO = () => process.env.SCENARIO || "premium";

export function risposte(via, metodo) {
  switch (via) {
    case "api/stato":
      return {
        casa: { versione: "2026.11.3", nome: "Casa Rossi" },
        centralino: { configurato: true, dentro: true, casa: "casa_rossi", dove: "centralino.gdahome.org" },
        versione: "1.19.33",
        porta: 8098,
        massimi: null,
        app: true,
        plance: [
          { id: "principale", nome: "Casa", principale: true, modello: "classica" },
          { id: "ingresso", nome: "Ingresso", modello: "muro" },
          { id: "cucina", nome: "Cucina", modello: "muro" },
          { id: "camera", nome: "Camera", modello: "muro" },
          { id: "soggiorno", nome: "Soggiorno", modello: "muro" },
        ],
        assistenza: { console: true },
        abbinamento: { attivo: false },
        prova: null,
        gestore: false,
        dispositivi: DISPOSITIVI,
      };
    case "api/licenza":
      return SCENARIO() === "base"
        ? { gdahome: { attiva: false, piano: "base" }, premium: false }
        : { gdahome: { attiva: true, piano: "premium", fino: null }, premium: true };
    case "api/utenti":
      return { utenti: [{ id: "a".repeat(32), nome: "Marta", amministratore: true }, { id: "b".repeat(32), nome: "Luca", amministratore: false }] };
    case "api/plance":
      return { plance: [
        { id: "principale", nome: "Casa", principale: true },
        { id: "ingresso", nome: "Ingresso", modello: "muro" },
        { id: "cucina", nome: "Cucina", modello: "muro" },
      ] };
    case "api/pannelli": {
      if (metodo === "POST") return { id: "pm_nuovo00004" };
      const premium = SCENARIO() !== "base";
      if (SCENARIO() === "vuoto" || SCENARIO() === "base") return { pannelli: [], premium, adb: true };
      const pannelli = PANNELLI.map((p) => ({ ...p }));
      if (SCENARIO() === "installa") {
        Object.assign(pannelli[3], { nome: "Soggiorno", lavoro: { titolo: "Copia di sicurezza in corso…", passi: PASSI(1) } });
      }
      if (SCENARIO() === "fatto") {
        Object.assign(pannelli[3], { nome: "Soggiorno", stato: "pronto", acceso: true, os: "2.0.0", app: "1.19.33", plancia: "Soggiorno", utente: "a".repeat(32), lavoro: { finito: true, passi: PASSI(5) } });
      }
      return { pannelli, premium, adb: true };
    }
    case "api/pannelli/rete":
      return { trovati: [
        { indirizzo: "192.168.1.53", modello: "86" },
        { indirizzo: "192.168.1.57", modello: "120" },
        { indirizzo: "192.168.1.51", modello: "86", gia: true },
      ] };
    case "api/pannelli/verifica":
      return {
        indirizzo: "192.168.1.53",
        modello: "86",
        schermo: "480×480",
        android: "8.1",
        adatto: true,
        verifiche: [
          { esito: "bene", detto: "ADB acceso, accesso root" },
          { esito: "bene", detto: "Partizione di sistema modificabile" },
          { esito: "bene", detto: "Spazio per la copia: 1,9 GB su 28 GB liberi in /share" },
          { esito: "giallo", detto: "WebView 61: si aggiorna durante l'installazione" },
        ],
      };
    case "api/aggiornamento":
    case "api/cruscotto":
    case "api/quadro":
    case "api/codice":
      return {};
    default:
      return undefined;
  }
}
