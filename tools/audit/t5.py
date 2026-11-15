from dbt import *
import sys
def run(tag,sigs=('pink.f32',),**kw):
    dbset(**kw); out=[]
    for s in sigs:
        r=M(s,warm=3000,secs=3); R[(tag,s)]=r
    return r
R={}
run('NEUTRO_DB',sigs=('pink.f32','side.f32','pinkq.f32','pinkl.f32'))
base=lambda s:R[('NEUTRO_DB',s)]
show('neutro DB (abs)',base('pink.f32'))
r=run('bass hp',pe_hp=dict(bass_enhancer_enable=1)); show('realce bajos en fila auriculares',r,base('pink.f32'))
r=run('bass sp',pe_sp=dict(bass_enhancer_enable=1)); show('realce bajos en fila altavoz',r,base('pink.f32'))
for b in (96,288): r=run(f'boost{b}',pe_hp=dict(bass_enhancer_enable=1),tun_hp=dict(bass_enhancer_boost=b)); show(f'refuerzo interno {b}',r,base('pink.f32'))
for sb in (0,96): r=run(f'surr{sb}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1,surround_boost=sb)); show(f'side virt amplitud {sb} (abs)',r)
for d in (1,48): r=run(f'dist{d}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1,headphone_virtualizer_steerer_source_distance=d)); show(f'side virt distancia {d} (abs)',r)
for m in (1,2): r=run(f'mode{m}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1),prof=dict(headphone_virtualizer_mode=m)); show(f'side virt modo {m} (abs)',r)
for a in (10,90): r=run(f'ang{a}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1),prof=dict(advanced_headphone_virtualizer_lr_angle=a)); show(f'side virt angulo {a} (abs)',r)
r=run('volmax',sigs=('pinkq.f32','pink.f32'),pe_hp=dict(volmax_boost=144))
show('volmax 144, señal baja',R[('volmax','pinkq.f32')],base('pinkq.f32')); show('volmax 144, señal media',R[('volmax','pink.f32')],base('pink.f32'))
r=run('hp',sigs=('pinkl.f32',),pe_hp=dict(hearing_protection_enable=1)); show('protección auditiva, señal fuerte',r,base('pinkl.f32'))
r=run('modeler',sigs=('pinkq.f32',),prof=dict(volume_modeler_enable=1)); show('modelador de volumen, señal baja',r,base('pinkq.f32'))
import pickle; pickle.dump({k:{'bands':v['bands'],'total':v['total'],'rms':v['rms']} for k,v in R.items()},open('r5.pkl','wb'))
