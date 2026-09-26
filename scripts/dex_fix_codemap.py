import struct, hashlib, zlib, sys, os
def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]

def fix(path):
    b=bytearray(open(path,'rb').read())
    if b[:4]!=b'dex\n': return None
    idx=b.rfind(b'BBbb.dgc')
    if idx<0: return None
    off1=struct.unpack_from('>I',b,idx-4)[0]
    cms=idx-0x20-off1
    item_size=struct.unpack_from('>I',b,cms+8)[0]
    itemdata_len=struct.unpack_from('>I',b,cms+0xc)[0]
    data_off=struct.unpack_from('>I',b,cms+0x10)[0]
    if item_size==0: return None
    count=itemdata_len//item_size
    code_start=cms+data_off
    code_end=code_start
    for i in range(count):
        it=cms+0x18+0x18*i
        rel=struct.unpack_from('>I',b,it)[0]
        size=struct.unpack_from('>I',b,it+4)[0]
        e=code_start+rel+size
        if e>code_end: code_end=e
    map_off=u32(b,0x34); n=u32(b,map_off)
    entries=[]
    for i in range(n):
        o=map_off+4+12*i
        entries.append([u16(b,o),u32(b,o+4),u32(b,o+8)])
    # already covered?
    covered=False
    for j,(t,sz,off) in enumerate(entries):
        if t==0x2001 and off<=code_start:
            nxt=entries[j+1][2] if j+1<len(entries) else len(b)
            if code_start < nxt: covered=True
    if covered:
        return (count, code_start, code_end, map_off, 'already-covered')
    entries.append([0x2001,count,code_start])
    # reorder: sort by offset, map_list (0x1000) will reference new location
    entries=[e for e in entries if e[0]!=0x1000]
    new_map_off=(len(b)+3)&~3
    pad=new_map_off-len(b)
    total=4+12*(len(entries)+1)
    new_map_off=(len(b)+pad)
    # append map_list entry
    entries.append([0x1000,1,new_map_off])
    entries.sort(key=lambda e:e[2])
    b+=b'\x00'*pad
    b+=struct.pack('<I',len(entries))
    for t,sz,off in entries:
        b+=struct.pack('<HHI I'.replace(' ',''),t,0,sz,off) if False else struct.pack('<HHI',t,0,sz)+struct.pack('<I',off)
    # update header
    struct.pack_into('<I',b,0x34,new_map_off)   # map_off
    struct.pack_into('<I',b,0x20,len(b))        # file_size
    data_off_hdr=u32(b,0x6c)
    struct.pack_into('<I',b,0x68,len(b)-data_off_hdr)  # data_size
    sig=hashlib.sha1(bytes(b[32:])).digest(); b[12:32]=sig
    chk=zlib.adler32(bytes(b[12:]))&0xffffffff; struct.pack_into('<I',b,8,chk)
    open(path,'wb').write(bytes(b))
    return (count, code_start, code_end, new_map_off, 'added')

if __name__=='__main__':
    d=sys.argv[1]
    for f in sorted(os.listdir(d)):
        if f.endswith('.dex'):
            r=fix(os.path.join(d,f))
            if r: print(f,r)
