import struct, hashlib, zlib, sys

def u32(b,o): return struct.unpack_from('<I',b,o)[0]

def str_at(b, off):
    p=off; size=0; shift=0
    while True:
        x=b[p]; p+=1; size |= (x&0x7f)<<shift
        if not (x&0x80): break
        shift+=7
    return b[p:p+size].decode('utf-8','replace')

def unfinal(path, desc='Lcom/ets100/secondary/app/EtsApp;'):
    b=bytearray(open(path,'rb').read())
    if b[:4]!=b'dex\n': return 0
    sid_sz=u32(b,0x38); sid_off=u32(b,0x3c)
    tid_sz=u32(b,0x40); tid_off=u32(b,0x44)
    cd_sz=u32(b,0x60); cd_off=u32(b,0x64)
    tidx=None
    for i in range(tid_sz):
        si=u32(b,tid_off+4*i)
        s=str_at(b,u32(b,sid_off+4*si))
        if s==desc: tidx=i; break
    if tidx is None: return 0
    n=0
    for j in range(cd_sz):
        base=cd_off+32*j
        if u32(b,base)==tidx:
            flags=u32(b,base+4)
            if flags & 0x10:
                struct.pack_into('<I',b,base+4,flags & ~0x10); n+=1
            break
    if n:
        sig=hashlib.sha1(bytes(b[32:])).digest(); b[12:32]=sig
        chk=zlib.adler32(bytes(b[12:])) & 0xffffffff; struct.pack_into('<I',b,8,chk)
        open(path,'wb').write(bytes(b))
    return n
if __name__=='__main__':
    print('unfinal count:', unfinal(sys.argv[1]))
