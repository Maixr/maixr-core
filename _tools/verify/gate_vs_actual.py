#!/usr/bin/env python3
"""对照门控通过 vs 实际含岛，定位净空失败的规模。"""
import importlib.util, os, struct, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

M=(1<<64)-1; K1=0x9E3779B97F4A7C15; K2=0xC2B2AE3D27D4EB4F; KM=0xFF51AFD7ED558CCD
def gate(cx,cz,d=4):
    h=((cx*K1)^(cz*K2)^0x5EED)&M
    h^=h>>33; h=(h*KM)&M; h^=h>>33
    return h%d==0

MARKERS={"minecraft:cactus","minecraft:dead_bush","minecraft:sugar_cane","minecraft:kelp_plant",
         "minecraft:brain_coral_block","minecraft:tube_coral_block","minecraft:glass","minecraft:lapis_block"}

def chunks(path):
    data=open(path,"rb").read()
    if len(data)<8192: return
    for idx in range(1024):
        off=struct.unpack_from(">I",data,idx*4)[0]>>8
        if off==0 or struct.unpack_from(">B",data,idx*4+3)[0]==0: continue
        start=off*4096; ln=struct.unpack_from(">i",data,start)[0]
        comp=data[start+4]; raw=data[start+5:start+4+ln]
        if comp==2: raw=zlib.decompress(raw)
        elif comp!=1: continue
        try: root=m.Nbt(raw).root()
        except Exception: continue
        yield (idx%32,idx//32), root

for p in sys.argv[1:]:
    rx=int(p.split('.')[-3]); rz=int(p.split('.')[-2])
    gate_pass=0; gate_pass_island=0; solid=0
    for (cx,cz), root in chunks(p):
        has_solid=False; has_island=False
        for sec in root.get("sections",[]):
            if not isinstance(sec,dict): continue
            bs=sec.get("block_states")
            if not isinstance(bs,dict): continue
            for pal in bs.get("palette",[]):
                nm=pal.get("Name","") if isinstance(pal,dict) else ""
                if nm and nm!="minecraft:air":
                    has_solid=True
                    if nm in MARKERS: has_island=True
        if not has_solid: continue
        solid+=1
        gx, gz = rx*32+cx, rz*32+cz
        if gate(gx,gz):
            gate_pass+=1
            if has_island: gate_pass_island+=1
    print(f"{os.path.basename(p)}: 已生成={solid} 门控通过={gate_pass} 其中含岛={gate_pass_island} "
          f"净空成功率={gate_pass_island/max(gate_pass,1)*100:.0f}%")
