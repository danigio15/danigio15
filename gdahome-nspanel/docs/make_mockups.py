#!/usr/bin/env python3
"""
Inserisce i render reali della plancia a muro di GdAHome (docs/renders/raw/*.png,
generati da docs/fotografa-il-muro.mjs)
in una cornice da pannello a muro, con proporzioni dei due modelli:
  - 86:  corpo 86 x 86 mm, schermo 3,95" quadrato (480x480)
  - 120: corpo ~74 x 120 mm, schermo 4,7" verticale (750x1334)
Le cornici sono generiche (nessun marchio).
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "renders", "raw")
OUT = os.path.join(HERE, "renders")

WALL_TOP, WALL_BOT = (236, 233, 228), (214, 210, 204)

def font(size, bold=False):
    names = ["DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
    for n in names:
        try: return ImageFont.truetype(n, size)
        except OSError: pass
    return ImageFont.load_default()

def wall(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(WALL_TOP, WALL_BOT)))
    return img

def rounded_mask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], r, fill=255)
    return m

def device(screen, body_size, screen_box, radius, color=(28, 30, 34)):
    """Corpo del pannello con vetro nero, schermo e riflesso."""
    bw, bh = body_size
    body = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    base = Image.new("RGBA", (bw, bh), color + (255,))
    # leggero gradiente sul corpo
    g = ImageDraw.Draw(base)
    for y in range(bh):
        k = int(14 * (1 - y / bh))
        g.line([(0, y), (bw, y)], fill=tuple(min(255, c + k) for c in color) + (255,))
    body.paste(base, (0, 0), rounded_mask((bw, bh), radius))
    # bordo lucido
    ImageDraw.Draw(body).rounded_rectangle([1, 1, bw - 2, bh - 2], radius, outline=(70, 74, 82, 255), width=2)
    x0, y0, x1, y1 = screen_box
    scr = screen.convert("RGBA").resize((x1 - x0, y1 - y0), Image.LANCZOS)
    body.paste(scr, (x0, y0), rounded_mask(scr.size, 6))
    # riflesso del vetro
    gloss = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss)
    gd.polygon([(0, 0), (int(bw * 0.55), 0), (int(bw * 0.15), bh), (0, bh)], fill=(255, 255, 255, 14))
    gloss = Image.composite(gloss, Image.new("RGBA", (bw, bh), (0, 0, 0, 0)), rounded_mask((bw, bh), radius))
    body.alpha_composite(gloss)
    return body

def on_wall(dev, pad=110, caption=None):
    w, h = dev.size[0] + pad * 2, dev.size[1] + pad * 2 + (70 if caption else 0)
    img = wall(w, h).convert("RGBA")
    # ombra morbida
    sh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([pad + 10, pad + 26, pad + dev.size[0] + 10, pad + dev.size[1] + 26], 40, fill=(0, 0, 0, 110))
    sh = sh.filter(ImageFilter.GaussianBlur(26))
    img.alpha_composite(sh)
    img.alpha_composite(dev, (pad, pad))
    if caption:
        d = ImageDraw.Draw(img)
        f = font(30, True)
        tw = d.textlength(caption, font=f)
        d.text(((w - tw) / 2, h - pad // 2 - 52), caption, font=f, fill=(60, 64, 72))
    return img

# Dimensioni fisiche (mm) per mantenere le proporzioni reali tra i due modelli
PX_MM = 12

def panel86(raw, px_mm=PX_MM):
    side = 70.9                      # 3,95" quadrato → lato visibile ~70,9 mm
    body = 86.0
    s, b = int(side * px_mm), int(body * px_mm)
    m = (b - s) // 2
    return device(raw, (b, b), (m, m, m + s, m + s), radius=int(7 * px_mm))

def panel120(raw, px_mm=PX_MM):
    sw, sh = 58.5, 103.9             # 4,7" 16:9 verticale
    bw, bh = 74.0, 120.0             # corpo
    w, h = int(sw * px_mm), int(sh * px_mm)
    BW, BH = int(bw * px_mm), int(bh * px_mm)
    mx, my = (BW - w) // 2, (BH - h) // 2
    return device(raw, (BW, BH), (mx, my, mx + w, my + h), radius=int(7 * px_mm))

def main():
    schermate = [
        ("avvio", "L'avvio"), ("stanza", "La stanza"), ("scene", "Le scene"), ("ingresso", "L'ingresso"), ("riposo", "Il riposo"),
    ]
    for chiave, titolo in schermate:
        for mod, maker in (("86", panel86), ("120", panel120)):
            p = os.path.join(RAW, f"muro_{chiave}_{mod}.png")
            if not os.path.exists(p):
                print("manca", p); continue
            img = on_wall(maker(Image.open(p)), caption=f"NSPanel Pro {mod} · {titolo}")
            out = os.path.join(OUT, f"muro_{chiave}_{mod}.jpg")
            img.convert("RGB").save(out, quality=88, optimize=True)
            print("scritto", out)

    # copertina: i due pannelli affiancati, in proporzione reale
    a, b = os.path.join(RAW, "muro_stanza_86.png"), os.path.join(RAW, "muro_stanza_120.png")
    if os.path.exists(a) and os.path.exists(b):
        d86, d120 = panel86(Image.open(a), 10), panel120(Image.open(b), 10)
        gap, pad = 140, 120
        w = d86.width + d120.width + gap + pad * 2
        h = max(d86.height, d120.height) + pad * 2 + 60
        img = wall(w, h).convert("RGBA")
        for dev, x in ((d86, pad), (d120, pad + d86.width + gap)):
            y = (h - 60 - dev.height) // 2
            sh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            ImageDraw.Draw(sh).rounded_rectangle([x + 10, y + 24, x + dev.width + 10, y + dev.height + 24], 40, fill=(0, 0, 0, 110))
            img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(24)))
            img.alpha_composite(dev, (x, y))
        d = ImageDraw.Draw(img)
        f = font(34, True)
        t = "GdAHome OS · plancia a muro"
        d.text(((w - d.textlength(t, font=f)) / 2, h - 90), t, font=f, fill=(60, 64, 72))
        out = os.path.join(OUT, "gdahome_os_pannelli.jpg")
        img.convert("RGB").save(out, quality=88, optimize=True); print("scritto", out)

if __name__ == "__main__":
    main()
