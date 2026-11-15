from base import *
print('bandas:',NAMES)
setp('on:1;'+NEUTRAL)
def T(tag,spec,sig,r0):
    r=M(sig,set=spec); show(tag,r,r0); setp(NEUTRAL); return r
# Virtualizer: "side" signal (L=-R): the mono sum is ~0 when unprocessed
sref=M('side.f32'); show('side neutral (abs)',sref)
T('side + headphone virtualizer 101','101:1','side.f32',sref)
# Dialogue: synthetic speech + background
vref=M('speech.f32'); show('speech neutral (abs)',vref)
for a in (4,8,16): T(f'Dialogue enhancer amount {a}',f'105:1;108:{a}','speech.f32',vref)
T('Dialogue enhancer on, amount 0','105:1;108:0','speech.f32',vref)
# Leveler: dynamic range of a 1 s loud / 1 s quiet signal (24 dB)
dref=M('dyn.f32',secs=6); print('dyn neutral range dB', round(dyn(dref),1))
for a in (0,5,10):
    r=M('dyn.f32',set=f'103:1;109:{a}',secs=6); print(f'leveler amount {a}: range', round(dyn(r),1),'tot',round(r['total']-dref['total'],1)); setp(NEUTRAL)
