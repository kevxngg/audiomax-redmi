from base import *
print('bandas:',NAMES)
setp('on:1;'+NEUTRAL)
def T(tag,spec,sig,r0):
    r=M(sig,set=spec); show(tag,r,r0); setp(NEUTRAL); return r
# Virtualizador: señal "side" (L=-R): la suma mono es ~0 sin procesar
sref=M('side.f32'); show('side neutro (abs)',sref)
T('side + virtualiz auric 101','101:1','side.f32',sref)
# Diálogo: voz sintética + fondo
vref=M('speech.f32'); show('voz neutro (abs)',vref)
for a in (4,8,16): T(f'Realce diálogo amount {a}',f'105:1;108:{a}','speech.f32',vref)
T('Realce diálogo enable, amount 0','105:1;108:0','speech.f32',vref)
# Nivelador: rango dinámico de señal 1 s fuerte / 1 s suave (24 dB)
dref=M('dyn.f32',secs=6); print('dyn neutro rango dB', round(dyn(dref),1))
for a in (0,5,10):
    r=M('dyn.f32',set=f'103:1;109:{a}',secs=6); print(f'nivelador amount {a}: rango', round(dyn(r),1),'tot',round(r['total']-dref['total'],1)); setp(NEUTRAL)
