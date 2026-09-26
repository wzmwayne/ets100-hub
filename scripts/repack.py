import sys, os, zipfile
orig, axml, dexdir, shimdex, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
zi = zipfile.ZipFile(orig)
zo = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for n in zi.namelist():
    if n == 'AndroidManifest.xml':
        continue
    if n.startswith('classes') and n.endswith('.dex'):
        continue
    if n.startswith('META-INF/'):
        b = n[len('META-INF/'):]
        if '/' not in b and (b.upper() == 'MANIFEST.MF' or b.upper().endswith('.SF') or b.upper().endswith('.RSA') or b.upper().endswith('.DSA') or b.upper().endswith('.EC')):
            continue
    if n.startswith('assets/meta-data/'):
        continue
    if 'libDexHelper' in n:
        continue
    info = zi.getinfo(n)
    data = zi.read(n)
    ni = zipfile.ZipInfo(n, date_time=info.date_time)
    ni.compress_type = info.compress_type
    ni.external_attr = info.external_attr
    zo.writestr(ni, data)

def add(name, path):
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_DEFLATED
    zo.writestr(info, open(path, 'rb').read())

zo.writestr(zipfile.ZipInfo('AndroidManifest.xml'), open(axml, 'rb').read())
add('classes.dex', shimdex)
files = [f for f in os.listdir(dexdir) if f.startswith('dex_out_') and f.endswith('.dex')]
files.sort(key=lambda x: int(x.split('_')[2].split('.')[0]))
for i, f in enumerate(files):
    add('classes%d.dex' % (i + 2), os.path.join(dexdir, f))
zo.close()
print('repacked:', out, 'dex count', 1 + len(files))
