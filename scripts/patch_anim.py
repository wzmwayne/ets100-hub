#!/usr/bin/env python3
# 构建期插桩：PK 反馈动画时长 -> Tuning.dur(correct,base)；下一题 300ms 延时 -> Tuning.delay(base)
# 注意：不触碰 3-2-1 答题倒计时。
import sys, os, subprocess, shutil, tempfile
def run(cmd): subprocess.check_call(cmd)
dex = sys.argv[1]
work = tempfile.mkdtemp(prefix="anim_")
sm = os.path.join(work, "sm")
run(["baksmali", "disassemble", dex, "-o", sm])
f = os.path.join(sm, "com/ets100/syncword/ui/fragment/WordBattleFragment.smali")
lines = open(f).read().split("\n")
out = []
dur_hits = 0
delay_hits = 0
i = 0
while i < len(lines):
    line = lines[i]
    out.append(line)
    st = line.strip()
    if st == "const-wide/16 v9, 0x3e8":
        j = i + 1
        while j < len(lines) and (lines[j].strip() == "" or lines[j].strip().startswith(".")):
            j += 1
        if j < len(lines) and "utils/g;->d(" in lines[j] and "v9, v10" in lines[j]:
            out += ["", "    invoke-static {v6, v9, v10}, Lcom/ets/unpack/hub/Tuning;->dur(ZJ)J", "", "    move-result-wide v9"]
            dur_hits += 1
    if st == "const-wide/16 v2, 0x12c":
        j = i + 1
        while j < len(lines) and (lines[j].strip() == "" or lines[j].strip().startswith(".")):
            j += 1
        if j < len(lines) and "postDelayed(Ljava/lang/Runnable;J)Z" in lines[j] and "v2, v3" in lines[j]:
            out += ["", "    invoke-static {v2, v3}, Lcom/ets/unpack/hub/Tuning;->delay(J)J", "", "    move-result-wide v2"]
            delay_hits += 1
    i += 1
if dur_hits == 0 and delay_hits == 0:
    sys.stderr.write("patch points not found\n"); sys.exit(2)
open(f, "w").write("\n".join(out))
outdir = os.path.join(work, "out"); os.makedirs(outdir)
run(["smali", "assemble", sm, "-o", os.path.join(outdir, "classes.dex")])
shutil.copyfile(os.path.join(outdir, "classes.dex"), dex)
shutil.rmtree(work, ignore_errors=True)
print("anim patched dur=%d delay=%d -> %s" % (dur_hits, delay_hits, dex))
