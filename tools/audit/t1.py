from base import *
def G(vals): return '110:'+','.join(str(v) for v in vals)
setp('on:1;'+NEUTRAL); ref=M(); show('NEUTRO (referencia)',ref)
def T(tag,spec,sig='pink.f32',r0=None):
    r=M(sig,set=spec); show(tag,r,r0 if r0 is not None else ref); setp(NEUTRAL); return r
T('Dolby apagado','on:0'); setp('on:1')
T('EQ +12dB graves (b0-4)',G([192]*5+[0]*15))
T('EQ -12dB 1k-2k (b10-12)',G([0]*10+[-192]*3+[0]*7))
T('EQ +6 dB todo (ganancia)',G([96]*20))
T('EQ +12 dB todo',G([192]*20))
T('EQ -12 dB todo',G([-192]*20))
for p in (1,2,3): T(f'IEQ preset {p} amount16',f'104:{p};107:16')
T('IEQ preset 2 amount 4','104:2;107:4')
T('IEQ preset 2 amount 0','104:2;107:0')
T('Realce bajos 111','111:1')
T('Bajo virtual 112','112:1')
T('Virtualizador auric 101','101:1')
T('Virtualizador altavoz 102','102:1')
