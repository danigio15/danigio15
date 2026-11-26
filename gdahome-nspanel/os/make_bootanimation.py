#!/usr/bin/env python3
"""Genera bootanimation.zip di GDAHome OS (formato Android: desc.txt + cartelle di PNG, zip senza compressione)."""
import io, math, sys, zipfile
from PIL import Image, ImageDraw, ImageFont

W = H = 480          # NSPanel Pro 86: Android centra/scala l'animazione sugli altri display
FPS = 20
BG = (14, 17, 22)
ACCENT = (245, 166, 35)
TEXT = (242, 244, 247)

def font(size):
    for name in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "Arial Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()

F_TITLE, F_SUB = font(44), font(16)

def house(draw, cx, cy, s, color, fill_g=BG):
    # tetto + corpo (come ic_gdahome.xml, viewport 48)
    k = s / 48.0
    pts = [(24, 4), (4, 20), (9, 20), (9, 42), (39, 42), (39, 20), (44, 20)]
    draw.polygon([(cx + (x - 24) * k, cy + (y - 24) * k) for x, y in pts], fill=color)
    # "G"
    r = 9 * k
    gx, gy = cx - 1.5 * k, cy + 3 * k
    draw.arc([gx - r, gy - r, gx + r, gy + r], 40, 330, fill=fill_g, width=max(2, int(3.2 * k)))
    draw.rectangle([gx, gy - 1.6 * k, gx + r, gy + 1.6 * k], fill=fill_g)

def frame(i, total, intro):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    if intro:
        t = min(1.0, i / (total - 1))
        e = 1 - (1 - t) ** 3
        size = 60 + 80 * e
        a = e
    else:
        t = i / total
        size = 140 + 6 * math.sin(t * 2 * math.pi)
        a = 1.0
    col = tuple(int(BG[c] + (ACCENT[c] - BG[c]) * a) for c in range(3))
    house(d, W / 2, H / 2 - 40, size, col)
    tc = tuple(int(BG[c] + (TEXT[c] - BG[c]) * a) for c in range(3))
    for text, f, y in (("GDAHome", F_TITLE, H / 2 + 60), ("OS", F_SUB, H / 2 + 110)):
        w = d.textlength(text, font=f)
        d.text(((W - w) / 2, y), text, font=f, fill=tc)
    if not intro:  # barra di caricamento a punti
        for k in range(3):
            on = (int(t * 6) % 3) == k
            x = W / 2 + (k - 1) * 22
            d.ellipse([x - 5, H - 70, x + 5, H - 60], fill=ACCENT if on else (60, 66, 76))
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()

def main(out):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", f"{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n")
        for i in range(20):
            z.writestr(f"part0/{i:03d}.png", frame(i, 20, True))
        for i in range(30):
            z.writestr(f"part1/{i:03d}.png", frame(i, 30, False))
    print("scritto", out)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "bootanimation.zip")
