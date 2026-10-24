#!/usr/bin/env python3
"""Draws the Mango Screen Cast logo, launcher icons and Android TV banner,
and the iOS app icon and logo.

    python3 tools/make_icons.py [android] [ios]   (both by default)
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android-tv", "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "ios", "MangoScreenCast", "Assets.xcassets")
BG = (14, 13, 18, 255)
SS = 4  # supersampling


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGBA", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, max(0.0, (x * 0.45 + y * 0.9) / (w * 0.45 + h * 0.9)))
            px[x, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
    return img


def mango(size):
    """Mango fruit with a leaf and a cast symbol, on transparency."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    # Fruit: a long, tilted, slightly kidney-shaped oval.
    mask = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((s * 0.27, s * 0.10, s * 0.73, s * 0.92), fill=255)
    d.ellipse((s * 0.33, s * 0.14, s * 0.79, s * 0.80), fill=255)
    mask = mask.rotate(-38, resample=Image.BICUBIC, center=(s / 2, s / 2))
    fruit = gradient((s, s), (196, 222, 70), (255, 70, 46))
    warm = gradient((s, s), (255, 206, 60), (255, 96, 40))
    fruit = Image.blend(fruit, warm, 0.55)
    img.paste(fruit, (0, 0), mask)
    # Highlight.
    hl = Image.new("L", (s, s), 0)
    ImageDraw.Draw(hl).ellipse((s * 0.30, s * 0.30, s * 0.46, s * 0.50), fill=90)
    hl = hl.filter(ImageFilter.GaussianBlur(s * 0.03))
    img.paste((255, 255, 255, 255), (0, 0), Image.composite(hl, Image.new("L", (s, s), 0), mask))
    # Leaf.
    leaf = Image.new("L", (s, s), 0)
    ImageDraw.Draw(leaf).ellipse((s * 0.66, s * 0.06, s * 0.94, s * 0.20), fill=255)
    leaf = leaf.rotate(-20, resample=Image.BICUBIC, center=(s * 0.74, s * 0.16))
    img.paste((76, 200, 120, 255), (0, 0), leaf)
    stem = ImageDraw.Draw(img)
    stem.line((s * 0.70, s * 0.22, s * 0.74, s * 0.12), fill=(90, 60, 30, 255), width=int(s * 0.03))
    # Cast symbol: three arcs and a dot, bottom-left of the fruit.
    d = ImageDraw.Draw(img)
    cx, cy = s * 0.36, s * 0.70
    white = (255, 255, 255, 255)
    width = int(s * 0.04)
    for r in (0.10, 0.18, 0.26):
        R = s * r
        d.arc((cx - R, cy - R, cx + R, cy + R), start=270, end=360, fill=white, width=width)
    R = s * 0.035
    d.ellipse((cx - R, cy - R, cx + R, cy + R), fill=white)
    return img.resize((size, size), Image.LANCZOS)


def launcher(size):
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle((0, 0, s - 1, s - 1), radius=s * 0.22, fill=BG)
    fruit = mango(int(size * 0.82)).resize((int(s * 0.82), int(s * 0.82)), Image.LANCZOS)
    img.alpha_composite(fruit, (int(s * 0.09), int(s * 0.09)))
    return img.resize((size, size), Image.LANCZOS)


def banner(width, height):
    s = SS
    img = Image.new("RGBA", (width * s, height * s), BG)
    fruit = mango(int(height * 0.66)).resize((int(height * 0.66 * s),) * 2, Image.LANCZOS)
    img.alpha_composite(fruit, (int(height * 0.08 * s), int(height * 0.17 * s)))
    d = ImageDraw.Draw(img)
    font_path = None
    for candidate in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                      "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
        if os.path.exists(candidate):
            font_path = candidate
            break
    big = ImageFont.truetype(font_path, int(height * 0.21 * s)) if font_path else ImageFont.load_default()
    small = ImageFont.truetype(font_path, int(height * 0.115 * s)) if font_path else ImageFont.load_default()
    x = int(height * 0.78 * s)
    d.text((x, int(height * 0.27 * s)), "Mango", font=big, fill=(255, 164, 27, 255))
    d.text((x, int(height * 0.54 * s)), "Screen Cast", font=small, fill=(244, 242, 247, 255))
    return img.resize((width, height), Image.LANCZOS)


def ios_icon(size):
    """Full-bleed and opaque: iOS rounds the corners itself."""
    img = Image.new("RGBA", (size, size), BG)
    fruit = mango(int(size * 0.80))
    img.alpha_composite(fruit, (int(size * 0.10), int(size * 0.10)))
    return img.convert("RGB")


def save(img, *parts, root=RES):
    path = os.path.join(root, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT))


def save_json(data, *parts):
    path = os.path.join(ASSETS, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def android():
    for folder, px in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        save(launcher(px), folder, "ic_launcher.png")
    save(mango(288), "drawable-nodpi", "logo.png")
    save(banner(320, 180), "drawable-xhdpi", "banner.png")


def ios():
    info = {"author": "xcode", "version": 1}
    save_json({"info": info}, "Contents.json")
    save(ios_icon(1024), "AppIcon.appiconset", "icon-1024.png", root=ASSETS)
    save_json({"images": [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios",
                           "size": "1024x1024"}], "info": info}, "AppIcon.appiconset", "Contents.json")
    save(mango(156), "Logo.imageset", "logo.png", root=ASSETS)
    save_json({"images": [{"filename": "logo.png", "idiom": "universal"}], "info": info},
              "Logo.imageset", "Contents.json")
    r, g, b = (f"0x{c:02X}" for c in BG[:3])
    save_json({"colors": [{"color": {"color-space": "srgb", "components": {
        "alpha": "1.000", "red": r, "green": g, "blue": b}}, "idiom": "universal"}], "info": info},
        "LaunchBackground.colorset", "Contents.json")


if __name__ == "__main__":
    targets = sys.argv[1:] or ["android", "ios"]
    if "android" in targets:
        android()
    if "ios" in targets:
        ios()
