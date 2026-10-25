#!/usr/bin/env python3
"""Makes the Mango Screen Cast For macOS/iOS logo, launcher icons and Android TV banner,
the iOS app icon and logo, and the Mac remote app's icon, from the artwork in
tools/logo-source.png (the mango with the rocket), each on a white circle.

    python3 tools/make_icons.py [android] [ios] [mac]   (all by default)
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android-tv", "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "ios", "MangoScreenCast", "Assets.xcassets")
MAC_ASSETS = os.path.join(ROOT, "ios", "MangoCastRemote", "Assets.xcassets")
SOURCE = os.path.join(ROOT, "tools", "logo-source.png")
FONTS = os.path.join(RES, "font")
BG = (14, 13, 18, 255)
WHITE = (255, 255, 255, 255)
SS = 4  # supersampling


def logo(size):
    """The artwork, trimmed to what's drawn, fitted inside a size x size square on transparency."""
    art = Image.open(SOURCE).convert("RGBA")
    art = art.crop(art.getbbox())
    scale = size / max(art.size)
    art = art.resize((max(1, round(art.width * scale)), max(1, round(art.height * scale))), Image.LANCZOS)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return img


def disc(size, fraction):
    """The logo on a white circle `fraction` of `size` across, centred in a size x size square."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = s * fraction
    ImageDraw.Draw(img).ellipse(((s - d) / 2, (s - d) / 2, (s + d) / 2, (s + d) / 2), fill=WHITE)
    # The artwork is a tilted oval, so two thirds of the circle keeps every corner of it inside.
    art = logo(int(d * 0.66))
    img.alpha_composite(art, ((s - art.width) // 2, (s - art.height) // 2))
    return img.resize((size, size), Image.LANCZOS)


def launcher(size):
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle((0, 0, s - 1, s - 1), radius=s * 0.22, fill=BG)
    img.alpha_composite(disc(s, 0.80))
    return img.resize((size, size), Image.LANCZOS)


def font(name, px):
    path = os.path.join(FONTS, name)
    return ImageFont.truetype(path, px) if os.path.exists(path) else ImageFont.load_default()


def banner(width, height):
    s = SS
    img = Image.new("RGBA", (width * s, height * s), BG)
    badge = disc(int(height * 0.74 * s), 1.0)
    img.alpha_composite(badge, (int(height * 0.10 * s), int(height * 0.13 * s)))
    d = ImageDraw.Draw(img)
    big = font("geist_bold.ttf", int(height * 0.20 * s))
    small = font("geist_medium.ttf", int(height * 0.115 * s))
    platform = font("geist_regular.ttf", int(height * 0.085 * s))
    x = int(height * 0.96 * s)
    d.text((x, int(height * 0.22 * s)), "Mango", font=big, fill=(255, 164, 27, 255))
    d.text((x, int(height * 0.47 * s)), "Screen Cast", font=small, fill=(244, 242, 247, 255))
    # Which devices it's for.
    d.text((x, int(height * 0.65 * s)), "for macOS/iOS", font=platform, fill=(169, 164, 181, 255))
    return img.resize((width, height), Image.LANCZOS)


def ios_icon(size):
    """Full-bleed and opaque: iOS rounds the corners itself."""
    img = Image.new("RGBA", (size, size), BG)
    img.alpha_composite(disc(size, 0.78))
    return img.convert("RGB")


def mac_icon(size):
    """macOS icon grid: a rounded square inset by about a tenth, on transparency."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    inset = s * 100 / 1024
    ImageDraw.Draw(img).rounded_rectangle((inset, inset, s - inset, s - inset), radius=s * 185 / 1024, fill=BG)
    img.alpha_composite(disc(s, 0.64))
    return img.resize((size, size), Image.LANCZOS)


def save(img, *parts, root=RES):
    path = os.path.join(root, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT))


def save_json(data, *parts, root=None):
    path = os.path.join(root or ASSETS, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def android():
    for folder, px in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        save(launcher(px), folder, "ic_launcher.png")
    # The bare artwork: the home screen puts it on its own white disc.
    save(logo(512), "drawable-nodpi", "logo.png")
    save(banner(320, 180), "drawable-xhdpi", "banner.png")


def ios():
    info = {"author": "xcode", "version": 1}
    save_json({"info": info}, "Contents.json")
    save(ios_icon(1024), "AppIcon.appiconset", "icon-1024.png", root=ASSETS)
    save_json({"images": [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios",
                           "size": "1024x1024"}], "info": info}, "AppIcon.appiconset", "Contents.json")
    save(disc(156, 1.0), "Logo.imageset", "logo.png", root=ASSETS)
    save_json({"images": [{"filename": "logo.png", "idiom": "universal"}], "info": info},
              "Logo.imageset", "Contents.json")
    r, g, b = (f"0x{c:02X}" for c in BG[:3])
    save_json({"colors": [{"color": {"color-space": "srgb", "components": {
        "alpha": "1.000", "red": r, "green": g, "blue": b}}, "idiom": "universal"}], "info": info},
        "LaunchBackground.colorset", "Contents.json")


def mac():
    info = {"author": "xcode", "version": 1}
    save_json({"info": info}, "Contents.json", root=MAC_ASSETS)
    images = []
    for points in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = points * scale
            name = f"icon-{points}@{scale}x.png"
            save(mac_icon(px), "AppIcon.appiconset", name, root=MAC_ASSETS)
            images.append({"filename": name, "idiom": "mac", "scale": f"{scale}x", "size": f"{points}x{points}"})
    save_json({"images": images, "info": info}, "AppIcon.appiconset", "Contents.json", root=MAC_ASSETS)


if __name__ == "__main__":
    targets = sys.argv[1:] or ["android", "ios", "mac"]
    if "android" in targets:
        android()
    if "ios" in targets:
        ios()
    if "mac" in targets:
        mac()
