import re, sys, xml.etree.ElementTree as ET
def shapes(svg):
    root = ET.parse(svg).getroot()
    out = []
    for el in root.iter():
        tag = el.tag.split('}')[-1]; a = el.attrib
        f = lambda k, d=0: float(a.get(k, d))
        if tag == 'path': out.append(a['d'])
        elif tag == 'line': out.append(f"M{f('x1')},{f('y1')} L{f('x2')},{f('y2')}")
        elif tag == 'circle':
            cx, cy, r = f('cx'), f('cy'), f('r')
            out.append(f"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} A{r},{r} 0 1 0 {cx - r},{cy}")
        elif tag == 'rect':
            x, y, w, h, rx = f('x'), f('y'), f('width'), f('height'), f('rx', a.get('ry', 0))
            if rx:
                out.append(f"M{x + rx},{y} H{x + w - rx} A{rx},{rx} 0 0 1 {x + w},{y + rx} V{y + h - rx} "
                           f"A{rx},{rx} 0 0 1 {x + w - rx},{y + h} H{x + rx} A{rx},{rx} 0 0 1 {x},{y + h - rx} "
                           f"V{y + rx} A{rx},{rx} 0 0 1 {x + rx},{y} Z")
            else:
                out.append(f"M{x},{y} H{x + w} V{y + h} H{x} Z")
    return out
name, size, colour, note = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
paths = '\n'.join(f'''    <path
        android:pathData="{d}"
        android:strokeWidth="2"
        android:strokeColor="{colour}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />''' for d in shapes(sys.argv[1]))
print(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Lucide "{name}" (ISC licence, third_party/lucide): {note} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
{paths}
</vector>''')
