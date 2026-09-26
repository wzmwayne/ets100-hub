import sys, os, zipfile
apk, dexdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
files = [f for f in os.listdir(dexdir) if f.startswith('dex_out_') and f.endswith('.dex')]
files.sort(key=lambda x: int(x.split('_')[2].split('.')[0]))
zi = zipfile.ZipFile(apk)
zo = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
kept = 0
for n in zi.namelist():
    if n == 'classes.dex' or (n.startswith('classes') and n.endswith('.dex')) or n.startswith('META-INF/'):
        continue
    zo.writestr(n, zi.read(n))
    kept += 1
for i, f in enumerate(files):
    name = 'classes.dex' if i == 0 else ('classes%d.dex' % (i + 1))
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_STORED
    zo.writestr(info, open(os.path.join(dexdir, f), 'rb').read())
zo.close()
print('kept entries:', kept, 'dex:', files)
