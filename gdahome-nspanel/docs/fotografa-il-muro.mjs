/* Render reali della plancia a muro di GdAHome alle misure dei NSPanel.
 *
 *   node docs/fotografa-il-muro.mjs /percorso/di/gdahomeapp
 *
 * Usa i file veri della plancia (officina/.../frontend) e la casa finta delle
 * prove a muro di GdAHome (e2e/helpers/casa-a-muro.js). Scrive i PNG in
 * docs/renders/raw/.
 *
 * Misure (CSS px = dp di Android):
 *   NSPanel Pro 86  : 480 x 480  px, densità 1   → 480 x 480 CSS
 *   NSPanel Pro 120 : 750 x 1334 px, densità 2   → 375 x 667 CSS
 */
import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const GDA = process.argv[2] ?? "/home/user/gdahomeapp";
const OFFICINA = join(GDA, "officina");
const FRONTEND = join(OFFICINA, "custom_components/dashboardmodern/frontend");
const QUI = dirname(fileURLToPath(import.meta.url));
const USCITA = join(QUI, "renders", "raw");
mkdirSync(USCITA, { recursive: true });

const { chromium } = await import(pathToFileURL(join(OFFICINA, "node_modules/playwright/index.mjs")).href)
  .catch(() => import("playwright"));
const aiuto = await import(pathToFileURL(join(FRONTEND, "e2e/helpers/casa-a-muro.js")).href);

const server = spawn("python3", ["-m", "http.server", "4173", "--bind", "127.0.0.1", "--directory", FRONTEND], {
  stdio: "ignore",
});
await new Promise((r) => setTimeout(r, 1200));

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM || "/opt/pw-browsers/chromium",
  args: ["--no-sandbox", "--disable-dev-shm-usage", "--no-proxy-server"],
});

const MISURE = [
  // 86 fotografato a densità 2 per avere immagini nitide: la disposizione è identica (480x480 CSS)
  { nome: "86", width: 480, height: 480, dpr: 2 },
  { nome: "120", width: 375, height: 667, dpr: 2 },
];

/* Le schermate: [nome, cosa fare prima della foto] */
const SCHERMATE = [
  ["stanza", async () => {}],
  ["scene", async (page) => page.locator('#dm-muro [data-mu-pagina="1"]').click()],
  ["ingresso", async (page) => page.locator('#dm-muro [data-mu-pagina="2"]').click()],
  ["riposo", async (page) => page.evaluate(() => window.dmMuro.riposa())],
];

async function scatta(misura) {
  const ctx = await browser.newContext({
    baseURL: "http://127.0.0.1:4173",
    viewport: { width: misura.width, height: misura.height },
    deviceScaleFactor: misura.dpr,
    isMobile: true,
    hasTouch: true,
    locale: "it-IT",
    timezoneId: "Europe/Rome",
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(60_000);
  await page.clock.install({ time: new Date("2026-11-27T19:42:00+01:00") });
  const testInfo = { project: { name: "chromium" }, outputPath: (n) => join(USCITA, n) };
  await aiuto.apriLaCasaAMuro(page, testInfo, { muro: aiuto.MURO_DI_PROVA });
  await page.locator("#dm-muro").waitFor({ state: "visible" });
  await page.locator("#cd-boot-overlay").waitFor({ state: "hidden", timeout: 15_000 }).catch(() => {});
  await page.addStyleTag({ content: "#cd-empty-banner,#dm-assist-tasto{display:none!important}" });
  for (const [nome, prima] of SCHERMATE) {
    await prima(page);
    await page.waitForTimeout(1500);
    const file = `muro_${nome}_${misura.nome}.png`;
    await page.screenshot({ path: join(USCITA, file) });
    console.log("render:", file);
  }
  await ctx.close();
}

try {
  for (const m of MISURE) await scatta(m);
} finally {
  await browser.close();
  server.kill();
}
