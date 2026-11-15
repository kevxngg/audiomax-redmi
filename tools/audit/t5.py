from dbt import *
import sys
def run(tag,sigs=('pink.f32',),**kw):
    dbset(**kw); out=[]
    for s in sigs:
        r=M(s,warm=3000,secs=3); R[(tag,s)]=r
    return r
R={}
run('NEUTRAL_DB',sigs=('pink.f32','side.f32','pinkq.f32','pinkl.f32'))
base=lambda s:R[('NEUTRAL_DB',s)]
show('neutral DB (abs)',base('pink.f32'))
r=run('bass hp',pe_hp=dict(bass_enhancer_enable=1)); show('bass enhancer in headphone row',r,base('pink.f32'))
r=run('bass sp',pe_sp=dict(bass_enhancer_enable=1)); show('bass enhancer in speaker row',r,base('pink.f32'))
for b in (96,288): r=run(f'boost{b}',pe_hp=dict(bass_enhancer_enable=1),tun_hp=dict(bass_enhancer_boost=b)); show(f'internal bass boost {b}',r,base('pink.f32'))
for sb in (0,96): r=run(f'surr{sb}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1,surround_boost=sb)); show(f'side virt width {sb} (abs)',r)
for d in (1,48): r=run(f'dist{d}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1,headphone_virtualizer_steerer_source_distance=d)); show(f'side virt distance {d} (abs)',r)
for m in (1,2): r=run(f'mode{m}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1),prof=dict(headphone_virtualizer_mode=m)); show(f'side virt mode {m} (abs)',r)
for a in (10,90): r=run(f'ang{a}',sigs=('side.f32',),pe_hp=dict(virtualizer_enable=1),prof=dict(advanced_headphone_virtualizer_lr_angle=a)); show(f'side virt angle {a} (abs)',r)
r=run('volmax',sigs=('pinkq.f32','pink.f32'),pe_hp=dict(volmax_boost=144))
show('volmax 144, quiet signal',R[('volmax','pinkq.f32')],base('pinkq.f32')); show('volmax 144, medium signal',R[('volmax','pink.f32')],base('pink.f32'))
r=run('hp',sigs=('pinkl.f32',),pe_hp=dict(hearing_protection_enable=1)); show('hearing protection, loud signal',r,base('pinkl.f32'))
r=run('modeler',sigs=('pinkq.f32',),prof=dict(volume_modeler_enable=1)); show('volume modeler, quiet signal',r,base('pinkq.f32'))
import pickle; pickle.dump({k:{'bands':v['bands'],'total':v['total'],'rms':v['rms']} for k,v in R.items()},open('r5.pkl','wb'))
