import struct, sys

PUSH_KEYS = [
 'getui','igexin','com.huawei.hms.support.api.push','com.huawei.hms.aaid',
 'com.huawei.agconnect','com.hihonor.push','com.hihonor.android.push',
 'com.xiaomi.push','com.xiaomi.mipush','com.xiaomi.xmsf','mipush',
 'com.vivo.push','com.vivo.pushservice','com.heytap.msp','com.heytap.mcs',
 'com.oppo.push','com.coloros.mcs','com.umeng.message','com.umeng.push',
 'cn.jpush','com.meizu','com.tencent.android.tpush',
]
PUSH_PERM_HINTS = ['getui.permission','mipush','jpush','umeng','push','agconnect','hihonor','heytap']

def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]
def read_len8(b,o):
    x=b[o]
    if x & 0x80: return ((x&0x7f)<<8)|b[o+1], o+2
    return x, o+1
def read_len16(b,o):
    x=u16(b,o)
    if x & 0x8000: return ((x&0x7fff)<<16)|u16(b,o+2), o+4
    return x, o+2
def parse_pool(b,off):
    hsz=u16(b,off+2); size=u32(b,off+4)
    cnt=u32(b,off+8); sty=u32(b,off+12); flags=u32(b,off+16)
    sstart=u32(b,off+20)
    offs=[u32(b,off+hsz+4*i) for i in range(cnt)]
    return dict(off=off,size=size,cnt=cnt,sty=sty,flags=flags,sstart=sstart,offs=offs)
def read_strings(b,p):
    utf8=bool(p['flags'] & 0x100); out=[]
    for i in range(p['cnt']):
        o=p['off']+p['sstart']+p['offs'][i]
        if utf8:
            n,o2=read_len8(b,o); l,o3=read_len8(b,o2); out.append(b[o3:o3+l].decode('utf-8','replace'))
        else:
            n,o2=read_len16(b,o); out.append(b[o2:o2+n*2].decode('utf-16le','replace'))
    return out

def main():
    path=sys.argv[1]
    b=open(path,'rb').read()
    pool=parse_pool(b,8)
    strs=read_strings(b,pool)
    # collect chunk list after pool
    chunks=[]
    c=pool['off']+pool['size']; end=u32(b,4)
    while c+8<=end:
        typ=u16(b,c); size=u32(b,c+4)
        if size==0: break
        chunks.append((typ,c,size))
        c+=size
    # element depth for each chunk
    depth=0; info=[]
    for (typ,off,size) in chunks:
        if typ==0x0102:
            ns=u32(b,off+16); name=u32(b,off+20)
            aoff=u16(b,off+24); asz=u16(b,off+26); acnt=u16(b,off+28)
            attrs={}
            a=off+16+aoff
            for i in range(acnt):
                an=u32(b,a+4); dtype=b[a+15]; data=u32(b,a+16)
                if dtype==0x03 and an<len(strs) and data<len(strs):
                    attrs[strs[an]]=strs[data]
                a+=asz
            info.append((off, depth, strs[name] if name<len(strs) else '', attrs))
            depth+=1
        elif typ==0x0103:
            depth-=1
            info.append((off, depth, '/', {}))
        else:
            info.append((off, depth, '', {}))
    # decide drops
    drop=set()
    starts=[(i,off,d,tag,attrs) for i,(off,d,tag,attrs) in enumerate(info) if tag and tag!='/']
    for (i,off,d,tag,attrs) in starts:
        if any((o>=off and o<off+0) for o in ()): pass
        name=attrs.get('name','')
        dropme=False
        low=(tag+' '+name).lower()
        if tag in ('uses-permission','uses-permission-sdk-23','permission'):
            if any(k in name.lower() for k in PUSH_KEYS) or any(h in name.lower() for h in PUSH_PERM_HINTS):
                dropme=True
        elif tag in ('service','receiver','provider','activity','meta-data','uses-library'):
            if any(k in name.lower() for k in PUSH_KEYS):
                dropme=True
        if dropme:
            # find matching END_ELEMENT at depth d
            j=i+1
            while j < len(info):
                o2,d2,tag2,_=info[j]
                if tag2=='/' and d2==d:
                    break
                j+=1
            if j<len(info):
                for k in range(i,j+1): drop.add(chunks[k][1])
    kept=[ch for ch in chunks if ch[1] not in drop]
    # serialize
    out=bytearray(b[:pool['off']+pool['size']])
    out+=b''.join(b[off:off+size] for (typ,off,size) in kept)
    struct.pack_into('<I',out,4,len(out))
    open(path,'wb').write(bytes(out))
    print('push elements dropped: %d chunks, remaining chunks: %d' % (len(drop), len(kept)))

main()
