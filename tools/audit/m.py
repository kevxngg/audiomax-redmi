import subprocess, time, numpy as np, os, sys, json
os.environ['MSYS_NO_PATHCONV']='1'
ADB=r'C:\platform-tools\adb.exe'
PKG='com.dolbyatmos.obsidian'; RCV=PKG+'/com.kevxngg.audiomax.MeasureReceiver'
DIR=f'/sdcard/Android/data/{PKG}/files'
def sh(cmd, su=False, binary=False):
    args=[ADB,'exec-out' if binary else 'shell']+(['su','-c',cmd if binary else '"'+cmd.replace('$',chr(92)+'$')+'"'] if su else [cmd])
    r=subprocess.run(args,capture_output=True,timeout=120)
    return r.stdout if binary else r.stdout.decode(errors='replace')
def bcast(**ex):
    parts=[]
    for k,v in ex.items():
        if isinstance(v,bool): parts.append(f'--ez {k} {str(v).lower()}')
        elif isinstance(v,int): parts.append(f'--ei {k} {v}')
        elif isinstance(v,float): parts.append(f'--ef {k} {v}')
        else: parts.append(f"--es {k} '{v}'")
    sh('logcat -c')
    sh(f"am broadcast -f 0x10000020 -n {RCV} "+' '.join(parts), su=True)
    t0=time.time(); log=''
    while time.time()-t0<14:
        time.sleep(0.7)
        log=sh('logcat -d -s AudioMaxMeasure:I')
        if 'MEASURE DONE' in log: break
    return [l.split('AudioMaxMeasure:')[-1].strip() for l in log.splitlines() if 'AudioMaxMeasure' in l]
def setp(spec): return bcast(set=spec)
def params(): return bcast(dump=True)
SR=48000
BANDS=[(40,100),(100,200),(200,400),(400,800),(800,1600),(1600,3200),(3200,6400),(6400,12800),(12800,20000)]
NAMES=['63','150','300','600','1.2k','2.4k','4.8k','9.6k','16k']
def analyze(raw):
    fr=np.frombuffer(raw,dtype=np.uint8); n=len(fr)//1032
    fr=fr[:n*1032].reshape(n,1032)[:,8:].astype(float); x=(fr-128)/128
    w=np.hanning(1024); P=np.mean(np.abs(np.fft.rfft(x*w,axis=1))**2,0)
    f=np.fft.rfftfreq(1024,1/SR)
    bands=[10*np.log10(P[(f>=a)&(f<b)].sum()+1e-12) for a,b in BANDS]
    rms=np.sqrt(np.mean(x**2,1))
    return dict(n=n,total=20*np.log10(np.sqrt(np.mean(x**2))+1e-9),bands=np.array(bands),rms=rms,clip=float(np.mean((fr==0)|(fr==255))))
def measure(sig, set=None, secs=4.0, warm=1500, tag=''):
    for i in range(3):
        r=_measure(sig,set,secs,warm)
        if r['n']>20: return r
        set=None
    return r
def _measure(sig, set=None, secs=4.0, warm=1500):
    ex=dict(sig=sig,secs=float(secs),warm=int(warm),out='r.bin')
    if set: ex['set']=set
    sh(f'rm -f {DIR}/r.bin',su=True)
    log=bcast(**ex)
    if any('FALLO' in l for l in log): raise RuntimeError(log)
    raw=sh(f'cat {DIR}/r.bin',su=True,binary=True)
    r=analyze(raw); r['log']=[l for l in log if not l.startswith('MEASURE')]
    return r
def show(tag,r,ref=None):
    b=r['bands'] if ref is None else r['bands']-ref['bands']
    t=r['total'] if ref is None else r['total']-ref['total']
    print(f"{tag:30s} tot {t:+6.1f} | "+' '.join(f'{v:+5.1f}' for v in b)+f" | n={r['n']} clip={r['clip']*100:.1f}%")
def dyn(r):
    s=np.sort(r['rms']); k=len(s)//3
    return 20*np.log10(np.median(s[-k:])/max(np.median(s[:k]),1e-6))
