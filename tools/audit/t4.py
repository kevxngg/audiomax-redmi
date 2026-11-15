from base import *
setp('on:1;'+NEUTRAL)
for sig in ('pinkq.f32','pink.f32','pinkl.f32'):
    r0=M(sig,warm=3500,secs=3)
    out=[f'{sig:10s} neutral {r0["total"]:6.1f}']
    for spec in ('103:1;109:0','103:1;109:5','103:1;109:10'):
        r=M(sig,set=spec,warm=3500,secs=3); out.append(f'{spec}: {r["total"]-r0["total"]:+5.1f}'); setp(NEUTRAL)
    print(' | '.join(out))
