import json, sys, time, urllib.request, urllib.parse
def get(params):
    url = 'https://commons.wikimedia.org/w/api.php?' + urllib.parse.urlencode(params)
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'MangoTV/1.0 (lifewirecg@gmail.com)'}), timeout=30) as r:
        return json.load(r)
films = {
 'notld': 'Night of the Living Dead 1968',
 'charade': 'Charade 1963 Audrey Hepburn',
 'girlfriday': 'His Girl Friday 1940',
 'metropolis': 'Metropolis 1927 film',
 'nosferatu': 'Nosferatu 1922',
 'general': 'The General 1926 Buster Keaton',
 'safetylast': 'Safety Last 1923 Harold Lloyd',
 'goldrush': 'The Gold Rush 1925 Chaplin',
}
out = {}
for key, q in films.items():
    r = get(dict(action='query', format='json', generator='search', gsrsearch=q, gsrnamespace=6, gsrlimit=40,
                 prop='imageinfo', iiprop='url|size|mime|extmetadata', iiextmetadatafilter='LicenseShortName'))
    rows = []
    for p in (r.get('query') or {}).get('pages', {}).values():
        ii = p['imageinfo'][0]
        if ii['mime'] not in ('image/jpeg', 'image/png'): continue
        w, h = ii['width'], ii['height']
        if w < 800 or w < h * 1.15: continue
        lic = ii.get('extmetadata', {}).get('LicenseShortName', {}).get('value', '')
        rows.append((w * h, p['title'], w, h, lic, ii['url']))
    rows.sort(reverse=True)
    out[key] = rows[:8]
    print('==', key, q)
    for _, t, w, h, lic, u in rows[:8]: print(f'   {w}x{h} {lic[:22]:22} {t[:80]}')
    time.sleep(0.5)
json.dump(out, open('/tmp/catalog/commons.json', 'w'), indent=1)
