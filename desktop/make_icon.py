"""Значок Claude Обход: звёздочка Claude на тёмной плашке. Делает icon.ico и icon.png."""
import math
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
BG_TOP = (52, 47, 43)
BG_BOT = (30, 29, 27)
ACC = (217, 119, 87)
LENS = [1.0, 0.76, 0.93, 0.70, 0.99, 0.80, 0.95, 0.72, 0.97, 0.78, 0.90, 0.74]


def render(size: int) -> Image.Image:
    S = 1024
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # плашка с мягким вертикальным градиентом
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        k = y / (S - 1)
        gd.line([(0, y), (S, y)], fill=tuple(int(BG_TOP[i] * (1 - k) + BG_BOT[i] * k) for i in range(3)) + (255,))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((24, 24, S - 24, S - 24), radius=220, fill=255)
    im.paste(grad, (0, 0), mask)
    # тонкая тёплая обводка
    ImageDraw.Draw(im).rounded_rectangle((24, 24, S - 24, S - 24), radius=220, outline=(90, 70, 58, 255), width=10)
    # свечение
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((262, 262, S - 262, S - 262), fill=ACC + (110,))
    glow = glow.filter(ImageFilter.GaussianBlur(70))
    im = Image.alpha_composite(im, Image.composite(glow, Image.new("RGBA", (S, S), (0, 0, 0, 0)), mask))
    # звёздочка: на мелких размерах лучи толще и ровнее, чтобы не рассыпались
    d = ImageDraw.Draw(im)
    c = S / 2
    small = size <= 32
    w = 92 if small else 62
    reach = 330 if small else 345
    lens = [1.0] * 12 if size <= 16 else LENS
    rays = 8 if size <= 16 else 12
    for i in range(rays):
        L = lens[i % len(lens)] if rays == 12 else 1.0
        a = math.radians(i * (360 / rays) + 8)
        r0, r1 = 40, reach * L
        x0, y0 = c + math.cos(a) * r0, c + math.sin(a) * r0
        x1, y1 = c + math.cos(a) * r1, c + math.sin(a) * r1
        d.line((x0, y0, x1, y1), fill=ACC, width=w)
        for x, y in ((x0, y0), (x1, y1)):
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=ACC)
    cw = w * 0.75
    d.ellipse((c - cw, c - cw, c + cw, c + cw), fill=ACC)
    return im.resize((size, size), Image.LANCZOS)


sizes = [16, 24, 32, 48, 64, 128, 256]
imgs = [render(s) for s in sizes]
imgs[-1].save(OUT / "icon.ico", format="ICO", sizes=[(s, s) for s in sizes], append_images=imgs[:-1])
render(256).save(OUT / "icon.png")
print("ok")
