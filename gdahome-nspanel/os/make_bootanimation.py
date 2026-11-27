#!/usr/bin/env python3
"""Genera bootanimation.zip di GDAHome OS (formato Android: desc.txt + cartelle di PNG, zip senza compressione)."""
import io, math, sys, zipfile
from PIL import Image, ImageDraw, ImageFont

W = H = 480          # NSPanel Pro 86: Android centra/scala l'animazione sugli altri display
FPS = 20
BG = (10, 26, 47)                 # blu notte GDAHome
ACCENT = (250, 204, 21)           # giallo fulmine
TEXT = (242, 246, 251)
BLUE_TOP, BLUE_BOT, DOOR = (14, 101, 200), (15, 163, 232), (11, 116, 222)
STROKE = (202, 138, 4)

def font(size):
    for name in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "Arial Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()

F_TITLE, F_SUB = font(44), font(16)

def logo(size):
    """Logo GDAHome (stessa geometria di ic_gdahome.xml, viewport 512), disegnato a 4x e ridotto."""
    S = 4 * size
    k = S / 512.0
    P = lambda pts: [(x * k, y * k) for x, y in pts]
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        t = y / (S - 1)
        gd.line([(0, y), (S, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(BLUE_TOP, BLUE_BOT)) + (255,))
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle([8 * k, 8 * k, 504 * k, 504 * k], 100 * k, fill=255)
    img.paste(grad, (0, 0), m)
    d = ImageDraw.Draw(img)
    d.polygon(P([(256, 96), (96, 236), (136, 236), (136, 401), (376, 401), (376, 236), (416, 236)]), fill="white")
    d.rounded_rectangle([226 * k, 298 * k, 286 * k, 400 * k], 12 * k, fill=DOOR)
    d.polygon(P([(277, 148), (216, 264), (258, 264), (233, 343), (311, 224), (266, 224)]),
              fill=ACCENT, outline=STROKE, width=max(1, int(5 * k)))
    return img.resize((size, size), Image.LANCZOS)

LOGO = {}

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
    sz = int(size)
    if sz not in LOGO:
        LOGO[sz] = logo(sz)
    lg = LOGO[sz].copy()
    if a < 1:
        lg.putalpha(lg.getchannel("A").point(lambda v: int(v * a)))
    img.paste(lg, (int(W / 2 - sz / 2), int(H / 2 - 40 - sz / 2)), lg)
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
