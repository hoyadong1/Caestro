import json, os, urllib.parse, urllib.request, concurrent.futures as cf
pick = json.load(open('pick.json', encoding='utf-8'))
jobs = [p for v in pick.values() for p in v['notes'].values()]
def get(p):
    dst = os.path.join('raw', p.replace('/', '__'))
    if os.path.exists(dst) and os.path.getsize(dst) > 1000: return 0
    url = 'https://raw.githubusercontent.com/sgossner/VSCO-2-CE/master/' + urllib.parse.quote(p)
    tmp = dst + '.part'
    urllib.request.urlretrieve(url, tmp); os.replace(tmp, dst); return 1
os.makedirs('raw', exist_ok=True)
with cf.ThreadPoolExecutor(8) as ex:
    n = sum(ex.map(get, jobs))
print('downloaded', n, 'of', len(jobs))
