#!/usr/bin/env python3
"""
L'animazione d'avvio di GdAHome OS, nello stile dell'avvio dell'app GdAHome
(app/lib/schermate/caricamento.dart): blu notte, bagliore blu, il marchio al
centro e l'anello d'oro che gli gira attorno.

    python3 make_bootanimation.py            → bootanimation-86.zip e bootanimation-120.zip

Formato Android: desc.txt + cartelle di PNG, zip SENZA compressione.
"""
import io, math, os, zipfile
from PIL import Image, ImageDraw, ImageFilter, ImageFont

QUI = os.path.dirname(os.path.abspath(__file__))
MARCHIO = Image.open(os.path.join(QUI, "gda-marchio.png")).convert("RGBA")

FONDO_ALTO, FONDO, FONDO_BASSO = (7, 13, 25), (11, 18, 32), (3, 8, 15)
BLU, ORO, NOME, RIGA = (59, 130, 246), (246, 184, 92), (230, 236, 245), (140, 156, 181)
FPS = 25

MODELLI = {
    "86": (480, 480),     # NSPanel Pro 86: 3,95" 480x480
    "120": (750, 1334),   # NSPanel Pro 120: 4,7" 750x1334
}

def font(size):
    # Inter, il carattere dell'app GdAHome (licenza SIL OFL, OFL-Inter.txt).
    for n in (os.path.join(QUI, "Inter-800.ttf"), "DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try: return ImageFont.truetype(n, size)
        except OSError: pass
    return ImageFont.load_default()

def fondo(W, H):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        a, b, k = (FONDO_ALTO, FONDO, t / 0.5) if t < 0.5 else (FONDO, FONDO_BASSO, (t - 0.5) / 0.5)
        d.line([(0, y), (W, y)], fill=tuple(int(x + (z - x) * k) for x, z in zip(a, b)))
    return img

def bagliore(W, H, cx, cy, r, forza):
    g = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(g).ellipse([cx - r, cy - r, cx + r, cy + r], fill=(22, 48, 122, int(150 * forza)))
    return g.filter(ImageFilter.GaussianBlur(r / 2.2))

def raggi(W, H, cx, cy, forza, giro):
    """I raggi blu dietro al marchio, come nell'avvio dell'app."""
    r = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(r)
    L = max(W, H)
    for i in range(10):
        a = math.radians(i * 36 + giro)
        w = math.radians(6)
        p = [(cx, cy), (cx + L * math.cos(a - w), cy + L * math.sin(a - w)), (cx + L * math.cos(a + w), cy + L * math.sin(a + w))]
        d.polygon(p, fill=(37, 99, 235, int(26 * forza)))
    return r.filter(ImageFilter.GaussianBlur(18))

def anello(W, H, cx, cy, r, inizio, lunghezza, alfa):
    a = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(a)
    passi = 24
    for k in range(passi):
        t = k / passi
        col = ORO + (int(255 * alfa * t),)
        s = inizio + lunghezza * t
        d.arc([cx - r, cy - r, cx + r, cy + r], s, s + lunghezza / passi + 1, fill=col, width=max(2, r // 45))
    return a

def scritte(img, W, cy_testo, alfa, sz):
    d = ImageDraw.Draw(img)
    f = font(sz)
    # Il nome si scrive GdAHome: con le sue maiuscole, non tutto maiuscolo.
    testo = "GdAHome"
    w = d.textlength(testo, font=f)
    col = tuple(int(FONDO[i] + (NOME[i] - FONDO[i]) * alfa) for i in range(3))
    d.text(((W - w) / 2, cy_testo), testo, font=f, fill=col)
    oro = tuple(int(FONDO[i] + (ORO[i] - FONDO[i]) * alfa) for i in range(3))
    lw = sz * 2
    d.rectangle([(W - lw) / 2, cy_testo + sz * 1.9, (W + lw) / 2, cy_testo + sz * 1.9 + max(2, sz // 8)], fill=oro)

def fotogramma(W, H, fase, t):
    """fase 'entra' (t 0..1) o 'gira' (t 0..1 ciclico)."""
    base = fondo(W, H).convert("RGBA")
    lato = int(min(W, H) * 0.36)
    cx, cy = W // 2, int(H * 0.44)
    if fase == "entra":
        e = 1 - (1 - t) ** 3
        scala, alfa, forza, giro = 0.82 + 0.18 * e, e, e, 0
    else:
        scala, alfa = 1.0, 1.0
        forza = 0.85 + 0.15 * math.sin(t * 2 * math.pi)
        giro = t * 36
    base.alpha_composite(bagliore(W, H, cx, cy, int(lato * 1.2), forza))
    base.alpha_composite(raggi(W, H, cx, cy, forza, giro))
    s = int(lato * scala)
    m = MARCHIO.resize((s, s), Image.LANCZOS)
    if alfa < 1:
        m.putalpha(m.getchannel("A").point(lambda v: int(v * alfa)))
    base.alpha_composite(m, (cx - s // 2, cy - s // 2))
    r = int(lato * 0.74)
    if fase == "entra":
        base.alpha_composite(anello(W, H, cx, cy, r, -90, 360 * e, 0.9 * e))
    else:
        base.alpha_composite(anello(W, H, cx, cy, r, -90 + 360 * t, 300, 0.9))
    scritte(base, W, cy + int(lato * 0.95), alfa, max(16, int(min(W, H) * 0.05)))
    buf = io.BytesIO()
    base.convert("RGB").save(buf, "PNG", optimize=True)
    return buf.getvalue()

def crea(modello, uscita):
    W, H = MODELLI[modello]
    with zipfile.ZipFile(uscita, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", f"{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n")
        n0, n1 = 25, 40
        for i in range(n0):
            z.writestr(f"part0/{i:03d}.png", fotogramma(W, H, "entra", i / (n0 - 1)))
        for i in range(n1):
            z.writestr(f"part1/{i:03d}.png", fotogramma(W, H, "gira", i / n1))
    print("scritto", uscita, os.path.getsize(uscita) // 1024, "KB")

if __name__ == "__main__":
    for m in MODELLI:
        crea(m, os.path.join(QUI, f"bootanimation-{m}.zip"))
