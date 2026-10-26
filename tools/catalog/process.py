import os, subprocess, sys
from PIL import Image
U = '/Applications/Upscayl.app/Contents/Resources'
FOCUS = {'safety_last': 0.22, 'the_general': 0.45, 'gold_rush': 0.4, 'notld': 0.4, 'nosferatu': 0.3,
         'girl_friday': 0.3, 'metropolis': 0.35}
def crop169(im, fy=0.5):
    w, h = im.size
    if w / h > 16 / 9:
        nw = round(h * 16 / 9); x = (w - nw) // 2
        return im.crop((x, 0, x + nw, h))
    nh = round(w * 9 / 16); y = round((h - nh) * fy)
    return im.crop((0, y, w, y + nh))
def upscayl(src, dst):
    subprocess.run([f'{U}/bin/upscayl-bin', '-i', src, '-o', dst, '-m', f'{U}/models', '-n', 'high-fidelity-4x', '-s', '2', '-f', 'png'],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
def webp(im, path, q):
    tmp = path + '.png'; im.save(tmp)
    subprocess.run(['cwebp', '-quiet', '-q', str(q), '-m', '6', tmp, '-o', path], check=True); os.remove(tmp)
jobs = sys.argv[1:] or sorted(os.listdir('/tmp/catalog/raw'))
for f in jobs:
    key = f.rsplit('.', 1)[0]
    hero = f'/tmp/catalog/out/show_{key}.webp'
    if os.path.exists(hero): continue
    im = crop169(Image.open('/tmp/catalog/raw/' + f).convert('RGB'), FOCUS.get(key, 0.5))
    base = (2560, 1440) if im.size[0] >= 2560 else (1280, 720)
    im.resize(base, Image.LANCZOS).save(f'/tmp/catalog/work/{key}.png')
    upscayl(f'/tmp/catalog/work/{key}.png', f'/tmp/catalog/work/{key}-up.png')
    up = Image.open(f'/tmp/catalog/work/{key}-up.png').convert('RGB')
    webp(up.resize((2560, 1440), Image.LANCZOS), hero, 80)
    webp(up.resize((512, 288), Image.LANCZOS), f'/tmp/catalog/out/show_{key}_tile.webp', 86)
    print('done', key, im.size, '->', base, flush=True)
print('ALL DONE', flush=True)
