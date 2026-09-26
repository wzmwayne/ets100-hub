import struct, sys

OLD_AW = 'com.secneo.apkwrapper.AW'
NEW_APP = 'com.ets100.secondary.app.EtsApp'

def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]

def read_len8(b,o):
    x=b[o]
    if x & 0x80: return ((x & 0x7f)<<8)|b[o+1], o+2
    return x, o+1

def read_len16(b,o):
    x=u16(b,o)
    if x & 0x8000: return ((x & 0x7fff)<<16)|u16(b,o+2), o+4
    return x, o+2

def parse_pool(b, off):
    typ=u16(b,off); hsz=u16(b,off+2); size=u32(b,off+4)
    cnt=u32(b,off+8); sty=u32(b,off+12); flags=u32(b,off+16)
    sstart=u32(b,off+20); ststart=u32(b,off+24)
    offs=[u32(b,off+hsz+4*i) for i in range(cnt)]
    return dict(off=off,hsz=hsz,size=size,cnt=cnt,sty=sty,flags=flags,
                sstart=sstart,ststart=ststart,offs=offs)

def read_all(b,p):
    out=[]; utf8=bool(p['flags'] & 0x100)
    for i in range(p['cnt']):
        o=p['off']+p['sstart']+p['offs'][i]
        if utf8:
            n,o2=read_len8(b,o); l,o3=read_len8(b,o2)
            out.append((n, b[o3:o3+l]))
        else:
            n,o2=read_len16(b,o)
            out.append((n, b[o2:o2+n*2]))
    return out, utf8

def dec(entry, utf8):
    n,d=entry
    return d.decode('utf-8','replace') if utf8 else d.decode('utf-16le','replace')

def enc(s, utf8):
    d=s.encode('utf-8') if utf8 else s.encode('utf-16le')
    return (len(s), d)

def build_pool(p, entries, utf8):
    p=dict(p); p['sty']=0; p['ststart']=0
    def e8(n):
        if n>0x7f: return bytes([(n>>8)&0x7f|0x80, n&0xff])
        return bytes([n&0x7f])
    def e16(n):
        if n>0x7fff: return struct.pack('<HH',((n>>16)&0x7fff)|0x8000, n&0xffff)
        return struct.pack('<H',n)
    buf=bytearray(); offs=[]
    for (n,d) in entries:
        offs.append(len(buf))
        if utf8: buf+=e8(n)+e8(len(d))+d+b'\x00'
        else: buf+=e16(n)+d+b'\x00\x00'
    while len(buf)%4: buf+=b'\x00'
    total=p['hsz']+4*p['cnt']+4*p['sty']+len(buf)
    out=bytearray()
    out+=struct.pack('<HHI',1,p['hsz'],total)
    out+=struct.pack('<IIIII',p['cnt'],p['sty'],p['flags'],
                     p['hsz']+4*p['cnt']+4*p['sty'],0)
    for x in offs: out+=struct.pack('<I',x)
    out+=buf
    return bytes(out)

def main():
    path, oldpkg, newpkg = sys.argv[1], sys.argv[2], sys.argv[3]
    appcls = sys.argv[4] if len(sys.argv) > 4 else NEW_APP
    assert len(oldpkg)==len(newpkg), 'new package must have same length'
    b=open(path,'rb').read()
    pool=parse_pool(b,8)
    entries,utf8=read_all(b,pool)
    idx2patch={}
    c=pool['off']+pool['size']
    end=u32(b,4)
    while c+8<=end:
        typ=u16(b,c); hsz=u16(b,c+2); size=u32(b,c+4)
        if size==0: break
        if typ==0x0102:  # START_ELEMENT
            name_idx=u32(b,c+8+4+4+4)  # node(8)+line(4)+comment(4)+ns(4)
            attr_start=u16(b,c+8+4+4+8)
            attr_size=u16(b,c+8+4+4+10)
            attr_count=u16(b,c+8+4+4+12)
            elem=dec(entries[name_idx],utf8)
            a=c+16+attr_start
            for i in range(attr_count):
                if a+attr_size>end: break
                an=u32(b,a+4)
                if an>=len(entries):
                    a+=attr_size; continue
                raw=u32(b,a+8)
                dtype=b[a+8+4+2+1]
                data=u32(b,a+8+4+4)
                aname=dec(entries[an],utf8)
                if dtype==0x03:
                    vidx=data
                elif raw!=0xFFFFFFFF:
                    vidx=raw
                else:
                    vidx=None
                if vidx is not None:
                    val=dec(entries[vidx],utf8)
                    newval=None
                    if elem=='application' and aname=='name' and (val==OLD_AW or val=='com.ets100.secondary.app.EtsApp'):
                        newval=appcls
                    elif aname=='package' and val==oldpkg:
                        newval=newpkg
                    elif aname in ('authorities','taskAffinity','process','targetPackage'):
                        if oldpkg in val:
                            newval=val.replace(oldpkg,newpkg)
                    elif aname in ('permission','readPermission','writePermission'):
                        if oldpkg in val:
                            newval=val.replace(oldpkg,newpkg)
                    elif elem in ('permission','uses-permission','uses-permission-sdk-23') and aname=='name':
                        if oldpkg in val:
                            newval=val.replace(oldpkg,newpkg)
                    if newval is not None and newval!=val:
                        idx2patch[vidx]=enc(newval,utf8)
                a+=attr_size
        c+=size
    for i,(n,d) in enumerate(entries):
        if i in idx2patch: entries[i]=idx2patch[i]
    newpool=build_pool(pool,entries,utf8)
    out=bytearray(struct.pack('<HHI',u16(b,0),8,0))
    out+=newpool+b[pool['off']+pool['size']:]
    struct.pack_into('<I',out,4,len(out))
    open(path,'wb').write(bytes(out))
    print('axml patched: package %s -> %s, %d strings updated' % (oldpkg,newpkg,len(idx2patch)))

main()
