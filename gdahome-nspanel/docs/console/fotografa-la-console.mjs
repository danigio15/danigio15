/* Render della console dell'add-on GdAHome con una casa finta.
 *
 *   node docs/console/fotografa-la-console.mjs /percorso/di/gdahomeapp [uscita]
 *
 * Serve ponte/console così com'è e risponde alle sue chiamate `api/*` con i
 * dati di `casa-finta.mjs`. Le schermate da fare sono in SCATTI. */
import { createServer } from "node:http";
import { mkdirSync, readFileSync } from "node:fs";
import { dirname, extname, join, normalize } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { risposte } from "./casa-finta.mjs";

const GDA = process.argv[2] ?? "/home/user/gdahomeapp";
const QUI = dirname(fileURLToPath(import.meta.url));
const USCITA = process.argv[3] ?? join(QUI, "render");
mkdirSync(USCITA, { recursive: true });
const CONSOLE = join(GDA, "ponte", "console");
const { chromium } = await import(pathToFileURL(join(GDA, "officina/node_modules/playwright/index.mjs")).href);

const TIPI = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".svg": "image/svg+xml", ".woff2": "font/woff2", ".ttf": "font/ttf" };
const server = createServer((req, res) => {
  const via = decodeURIComponent(req.url.split("?")[0]);
  const dove = join(CONSOLE, normalize(via === "/" ? "/index.html" : via));
  try {
    res.writeHead(200, { "content-type": TIPI[extname(dove)] || "application/octet-stream" });
    res.end(readFileSync(dove));
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((r) => server.listen(4180, "127.0.0.1", r));

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || "/opt/pw-browsers/chromium", args: ["--no-proxy-server"] });

/* Le schermate: nome, scenario della casa finta, sezione, cosa fare prima. */
const SCATTI = [
  ["pannelli", "premium", "pannelli"],
  ["pannelli-menu", "premium", "pannelli", async (p) => p.locator('[data-render="menu-pm_cucina0002"]').click()],
  ["pannelli-installa", "installa", "pannelli"],
  ["nuovo-1", "premium", "nuovo-pannello", async (p) => p.locator("#aggiungi-pannello").click().catch(() => {})],
  ["nuovo-1b-rete", "premium", "nuovo-pannello", async (p) => {
    await p.click("#cerca-in-rete");
  }],
  ["nuovo-2-verificato", "premium", "nuovo-pannello", async (p) => {
    await p.fill("#indirizzo-pannello", "192.168.1.53");
    await p.click("#cerca-pannello");
    await p.waitForTimeout(500);
    await p.fill("#nome-pannello", "Soggiorno");
    await p.selectOption("#plancia-pannello", "soggiorno");
  }],
  ["nuovo-3-installa", "installa", "nuovo-pannello", async (p) => {
    await p.fill("#indirizzo-pannello", "192.168.1.53");
    await p.click("#cerca-pannello");
    await p.waitForTimeout(500);
    await p.fill("#nome-pannello", "Soggiorno");
    await p.selectOption("#plancia-pannello", "soggiorno");
    await p.click("#installa-pannello");
  }],
  ["pannelli-vuoto", "vuoto", "pannelli"],
  ["pannelli-base", "base", "pannelli"],
];
const SOLO = process.env.SOLO ? process.env.SOLO.split(",") : null;

for (const larghezza of [1280, 400]) {
  for (const [nome, scenario, sezione, prima] of SCATTI) {
    if (SOLO && !SOLO.includes(nome)) continue;
    process.env.SCENARIO = scenario;
    const ctx = await browser.newContext({ viewport: { width: larghezza, height: 900 }, deviceScaleFactor: 2, locale: "it-IT", colorScheme: "light" });
    const page = await ctx.newPage();
    page.on("pageerror", (e) => console.log("ERRORE nella pagina:", e.message));
    await page.route("**/api/**", async (route) => {
      const u = new URL(route.request().url());
      const r = risposte(u.pathname.replace(/^\//, ""), route.request().method());
      if (r === undefined) return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(r) });
    });
    await page.goto(`http://127.0.0.1:4180/index.html#${sezione}`);
    await page.waitForTimeout(1200);
    if (prima) await prima(page);
    await page.waitForTimeout(900);
    const file = `${nome}_${larghezza}.png`;
    await page.screenshot({ path: join(USCITA, file), fullPage: true });
    console.log("render:", file);
    await ctx.close();
  }
}
await browser.close();
server.close();
