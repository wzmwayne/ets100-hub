import sys, re
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace('android:name="com.secneo.apkwrapper.AW"',
              'android:name="com.ets100.secondary.app.EtsApp"')
s = re.sub(r'\s*android:appComponentFactory="[^"]*"', '', s)
s = re.sub(r'\s*<provider[^>]*com\.secneo\.apkwrapper\.CP[^>]*/>', '', s)
open(p, 'w', encoding='utf-8').write(s)
print('manifest patched: app=EtsApp, appComponentFactory removed, CP provider removed')
