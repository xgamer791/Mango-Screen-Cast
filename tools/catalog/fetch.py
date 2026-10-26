import json, re, sys, time, urllib.request, urllib.parse
def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'MangoTV/1.0'}), timeout=30) as r:
                return json.load(r)
        except Exception as e:
            if '404' in str(e): return None
            time.sleep(2 + attempt * 2)
    return None
titles = [l.strip() for l in open(sys.argv[1]) if l.strip() and not l.startswith('#')]
out = {}
for t in titles:
    show = get('https://api.tvmaze.com/singlesearch/shows?q=' + urllib.parse.quote(t))
    time.sleep(0.6)
    if not show: print('MISSING', t); continue
    imgs = get(f"https://api.tvmaze.com/shows/{show['id']}/images") or []
    time.sleep(0.6)
    bgs = [i for i in imgs if i.get('type') == 'background']
    def res(i): o = i['resolutions'].get('original', {}); return (o.get('width') or 0) * (o.get('height') or 0)
    bgs.sort(key=lambda i: (i.get('main', False), res(i)), reverse=True)
    best = bgs[0]['resolutions']['original'] if bgs else None
    net = (show.get('network') or {}).get('name') or (show.get('webChannel') or {}).get('name')
    summary = re.sub('<[^>]+>', '', show.get('summary') or '')
    out[t] = dict(id=show['id'], name=show['name'], net=net, premiered=show.get('premiered'), ended=show.get('ended'),
                  status=show.get('status'), genres=show.get('genres'), type=show.get('type'), summary=summary,
                  poster=(show.get('image') or {}).get('original'), background=best, backgrounds=len(bgs))
    print(f"{t!r:40} -> {show['name']!r:38} {net!s:14} {show.get('premiered')}..{show.get('ended')} bg={len(bgs)} {best and (best.get('width'), best.get('height'))}")
json.dump(out, open(sys.argv[2], 'w'), indent=1)
