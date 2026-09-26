import struct, sys, os, hashlib, zlib

def u32(d,o): return struct.unpack_from("<I",d,o)[0]
def be32(d,o): return struct.unpack_from(">I",d,o)[0]
def uleb(d,o):
    v=0;s=0
    while True:
        c=d[o];o+=1;v|=(c&0x7f)<<s
        if not c&0x80: return v,o
        s+=7

def restore(src,out,report):
    d=bytearray(open(src,'rb').read())
    idx=d.rfind(b'BBbb.dgc')
    if idx<0:
        report.append("%s: NO BBbb.dgc"%os.path.basename(src)); open(out,'wb').write(d); return
    off1=be32(d,idx-4); cms=idx-0x20-off1
    item_size=be32(d,cms+8); idl=be32(d,cms+0xc); data_off=be32(d,cms+0x10)
    cnt=idl//item_size if item_size else 0
    code_start=cms+data_off; code_end=idx-4
    ents=[]
    for k in range(cnt):
        it=cms+0x18+0x18*k
        rel=be32(d,it); base=be32(d,it+0x10)
        orig,_=uleb(d,base)
        ents.append((rel,base,orig))
    ents.sort()
    hdrbad=0; small=0; copied=0; zerofix=0; maxinsns=0
    for k,(rel,base,orig) in enumerate(ents):
        end=ents[k+1][0] if k+1<len(ents) else (code_end-code_start)
        slot=end-rel
        insns=u32(d,orig+12)
        ncopy=16+2*insns
        maxinsns=max(maxinsns,ncopy)
        if d[orig:orig+16]!=d[code_start+rel:code_start+rel+16]: hdrbad+=1
        if ncopy>slot: small+=1; continue
        if ncopy>len(d)-orig: small+=1; continue
        if not any(d[orig+16:orig+ncopy]): zerofix+=1
        d[orig:orig+ncopy]=d[code_start+rel:code_start+rel+ncopy]
        copied+=1
    data_end=u32(d,0x6c)+u32(d,0x68)
    if data_end<len(d): del d[data_end:]
    struct.pack_into("<I",d,0x20,len(d))
    d[0x0c:0x20]=hashlib.sha1(bytes(d[0x20:])).digest()
    struct.pack_into("<I",d,0x08,zlib.adler32(bytes(d[0x0c:]))&0xffffffff)
    open(out,'wb').write(d)
    report.append("%s items=%d copied=%d hdrbad=%d small=%d zeroed_fixed=%d maxcopy=0x%x newlen=0x%x"%(
        os.path.basename(src),cnt,copied,hdrbad,small,zerofix,maxinsns,len(d)))

if __name__=="__main__":
    src_dir=sys.argv[1]; out_dir=sys.argv[2]
    os.makedirs(out_dir,exist_ok=True)
    rep=[]
    i=0
    while os.path.exists(os.path.join(src_dir,"dex_%d.raw"%i)):
        restore(os.path.join(src_dir,"dex_%d.raw"%i), os.path.join(out_dir,"dex_out_%d.dex"%i), rep)
        i+=1
    print("\n".join(rep))
