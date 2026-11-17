"""Generate KnowHub app icons (adaptive foreground, legacy, iOS, web favicon)."""
from PIL import Image, ImageDraw, ImageFont
import os, math

OUT = r"E:\KnowHub"
RES = os.path.join(OUT, r"app\androidApp\src\main\res")
IOS = os.path.join(OUT, r"app\iosApp\iosApp\Assets.xcassets\AppIcon.appiconset")
DOCS = os.path.join(OUT, r"docs\screenshots")
WEB = os.path.join(OUT, r"app\webApp\src\webMain\resources")

FONT = r"C:\Windows\Fonts\seguisb.ttf"
TOP = (48, 86, 211)      # #3056D3
BOTTOM = (107, 135, 255)  # #6B87FF
TEAL = (45, 212, 191)

SIZE = 1024


def gradient(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            r = int(TOP[0] + (BOTTOM[0] - TOP[0]) * t)
            g = int(TOP[1] + (BOTTOM[1] - TOP[1]) * t)
            b = int(TOP[2] + (BOTTOM[2] - TOP[2]) * t)
            # soft radial highlight top-left
            dx, dy = x / size - 0.24, y / size - 0.20
            glow = max(0.0, 1.0 - math.hypot(dx, dy) * 2.6) * 26
            px[x, y] = (min(255, int(r + glow)), min(255, int(g + glow)), min(255, int(b + glow)))
    return img


def draw_mark(img: Image.Image, color=(255, 255, 255, 255), scale=1.0):
    """Draw the K monogram with a small sparkle; scale relative to canvas."""
    size = img.size[0]
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    font = ImageFont.truetype(FONT, int(size * 0.56 * scale))
    bbox = d.textbbox((0, 0), "K", font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text(((size - w) / 2 - bbox[0], (size - h) / 2 - bbox[1] - size * 0.012), "K", font=font, fill=color)

    # sparkle accent (four-point star) at upper right of the K
    cx = size * 0.5 + size * 0.262 * scale
    cy = size * 0.5 - size * 0.248 * scale
    r = size * 0.034 * scale
    star = [(cx, cy - r), (cx + r * 0.28, cy - r * 0.28), (cx + r, cy),
            (cx + r * 0.28, cy + r * 0.28), (cx, cy + r), (cx - r * 0.28, cy + r * 0.28),
            (cx - r, cy), (cx - r * 0.28, cy - r * 0.28)]
    d.polygon(star, fill=(255, 255, 255, 190))
    img.alpha_composite(layer)
    return img


def rounded(img: Image.Image, radius_ratio=0.225) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def circle(img: Image.Image) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main():
    square = gradient(SIZE).convert("RGBA")
    draw_mark(square, scale=1.0)
    rounded_master = rounded(square)
    circle_master = circle(square)

    # iOS 1024 (opaque, no rounding; system masks)
    os.makedirs(IOS, exist_ok=True)
    square.convert("RGB").save(os.path.join(IOS, "app-icon-1024.png"))

    # Android legacy mipmaps
    for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        rounded_master.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        circle_master.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher_round.png"))

    # Adaptive foreground: transparent canvas, mark inside safe zone
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    draw_mark(fg, scale=0.52)
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_fg.png"))
    fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_mono.png"))

    # Docs preview + web favicon
    os.makedirs(DOCS, exist_ok=True)
    rounded_master.resize((512, 512), Image.LANCZOS).save(os.path.join(DOCS, "app-icon.png"))
    os.makedirs(WEB, exist_ok=True)
    rounded_master.resize((64, 64), Image.LANCZOS).save(os.path.join(WEB, "favicon.png"))
    print("icons generated")


if __name__ == "__main__":
    main()
