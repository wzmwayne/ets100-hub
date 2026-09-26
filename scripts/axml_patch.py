import struct, sys

def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]

def read_len8(b,o):
    x=b[o]
    if x & 0x80:
        return ((x & 0x7f)<<8)|b[o+1], o+2
    return x, o+1

def read_len16(b,o):
    x=u16(b,o)
    if x & 0x8000:
        return ((x & 0x7fff)<<16)|u16(b,o+2), o+4
    return x, o+2

def parse_pool(b, off):
    typ=u16(b,off); hsz=u16(b,off+2); size=u32(b,off+4)
    assert typ==1, 'not string pool'
    cnt=u32(b,off+8); sty=u32(b,off+12); flags=u32(b,off+16)
    sstart=u32(b,off+20); ststart=u32(b,off+24)
    offs=[u32(b,off+hsz+4*i) for i in range(cnt)]
    return dict(off=off,hsz=hsz,size=size,cnt=cnt,sty=sty,flags=flags,
                sstart=sstart,ststart=ststart,offs=offs)

def read_all(b,p):
    strings=[]
    utf8 = bool(p['flags'] & 0x100)
    for i in range(p['cnt']):
        o=p['off']+p['sstart']+p['offs'][i]
        if utf8:
            u16len,o2=read_len8(b,o)
            u8len,o3=read_len8(b,o2)
            data=b[o3:o3+u8len]
            strings.append((u16len,data))
        else:
            n,o2=read_len16(b,o)
            data=b[o2:o2+n*2]
            strings.append((n,data))
    return strings, utf8

def build_pool(p, strings, utf8):
    p = dict(p); p['sty'] = 0; p['ststart'] = 0
    def enc8(n):
        if n>0x7f: return bytes([ (n>>8)&0x7f | 0x80, n & 0xff ])
        return bytes([n & 0x7f])
    def enc16(n):
        if n>0x7fff: return struct.pack('<HH',((n>>16)&0x7fff)|0x8000, n & 0xffff)
        return struct.pack('<H',n)
    buf=bytearray(); offs=[]
    for (n,data) in strings:
        offs.append(len(buf))
        if utf8:
            buf+=enc8(n)+enc8(len(data))+data+b'\x00'
        else:
            buf+=enc16(n)+data+b'\x00\x00'
    while len(buf)%4: buf+=b'\x00'
    out=bytearray()
    total=p['hsz']+4*p['cnt']+4*p['sty']+len(buf)
    out+=struct.pack('<HHI',1,p['hsz'],total)
    out+=struct.pack('<IIII',p['cnt'],p['sty'],p['flags'],p['hsz']+4*p['cnt']+4*p['sty'])
    out+=struct.pack('<I',p['ststart'])
    for x in offs: out+=struct.pack('<I',x)
    out+=buf
    return bytes(out)

def main():
    path=sys.argv[1]; old=sys.argv[2]; new=sys.argv[3]
    b=open(path,'rb').read()
    typ=u16(b,0); assert typ==3
    pool=parse_pool(b,8)
    strings,utf8=read_all(b,pool)
    idx=None
    for i,(n,data) in enumerate(strings):
        s=data.decode('utf-8','ignore') if utf8 else data.decode('utf-16le','ignore')
        if s==old: idx=i; break
    if idx is None:
        print('target not found:',old); sys.exit(2)
    nb=new.encode('utf-8') if utf8 else new.encode('utf-16le')
    strings[idx]=(len(new),nb)
    newpool=build_pool(pool,strings,utf8)
    tail=b[pool['off']+pool['size']:]
    out=bytearray()
    out+=struct.pack('<HHI',typ,8,0)  # placeholder size
    out+=newpool+tail
    struct.pack_into('<I',out,4,len(out))
    open(path,'wb').write(bytes(out))
    print('patched string[%d]: %r -> %r' % (idx,old,new))

main()
