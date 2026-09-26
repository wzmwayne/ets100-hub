import os, re, sys
res = sys.argv[1]
renames = {}
for dp, dn, fn in os.walk(res):
    for f in fn:
        if '$' in f:
            base, ext = os.path.splitext(f)
            newbase = base.replace('$', '')
            newf = newbase + ext
            renames[base] = newbase          # key includes '$'
            os.rename(os.path.join(dp, f), os.path.join(dp, newf))
keys = sorted(renames.keys(), key=len, reverse=True)
changed = 0
for dp, dn, fn in os.walk(res):
    for f in fn:
        if not f.endswith('.xml'):
            continue
        p = os.path.join(dp, f)
        try:
            s = open(p, encoding='utf-8').read()
        except Exception:
            continue
        orig = s
        for k in keys:
            s = s.replace(k, renames[k])      # k already contains '$'
        if s != orig:
            open(p, 'w', encoding='utf-8').write(s)
            changed += 1
print('renamed files:', len(renames), 'updated xml:', changed)
