import struct, hashlib, zlib, sys, os

def patch_dex(path, oldpkg, newpkg):
    assert len(oldpkg)==len(newpkg)
    b=bytearray(open(path,'rb').read())
    if b[:4]!=b'dex\n': return 0
    sids_size=struct.unpack_from('<I',b,0x38)[0]
    sids_off=struct.unpack_from('<I',b,0x3c)[0]
    n=0
    for i in range(sids_size):
        off=struct.unpack_from('<I',b,sids_off+4*i)[0]
        p=off; size=0; shift=0
        while True:
            x=b[p]; p+=1
            size |= (x & 0x7f)<<shift
            if not (x & 0x80): break
            shift+=7
        old=bytes(b[p:p+size])
        try: s=old.decode('utf-8')
        except Exception: continue
        if s==oldpkg:
            nb=newpkg.encode('utf-8')
            assert len(nb)==len(old)
            b[p:p+size]=nb; n+=1
    sig=hashlib.sha1(bytes(b[32:])).digest()
    b[12:32]=sig
    chk=zlib.adler32(bytes(b[12:])) & 0xffffffff
    struct.pack_into('<I',b,8,chk)
    open(path,'wb').write(bytes(b))
    return n

if __name__=='__main__':
    d=sys.argv[1]; oldpkg=sys.argv[2]; newpkg=sys.argv[3]
    total=0
    for f in sorted(os.listdir(d)):
        if f.endswith('.dex'):
            c=patch_dex(os.path.join(d,f),oldpkg,newpkg)
            if c: print('  %s: %d strings' % (f,c)); total+=c
    print('total patched:',total)
