from dbt import *
def run(sig,**kw):
    dbset(**kw); return M(sig,warm=3000,secs=3)
def hi(r,q=0.3):
    s=np.sort(r['rms']); return 20*np.log10(np.mean(s[-max(1,int(len(s)*q)):])+1e-9)
D=dict(dialog_enhancer_enable=1,dialog_enhancer_amount=16)
for d in (0,16):
    r=run('speech.f32',pe_hp={**D,'dialog_enhancer_ducking':d}); print(f'atenuar fondo {d:2d}: voz(alto) {hi(r):6.1f}  fondo(huecos) {low(r):6.1f}  dif {hi(r)-low(r):5.1f}')
for e,a in ((0,0),(1,8),(1,16)):
    r=run('reverb.f32',prof=dict(reverb_suppression_enable=e,reverb_suppression_amount=a)); print(f'reverb sup {e} amt {a:2d}: directo {hi(r):6.1f} cola {low(r):6.1f} dif {hi(r)-low(r):5.1f}')
V=dict(virtualizer_enable=1)
b=run('side.f32',pe_hp=V)
for k in ('mi_adaptive_virtualizer_steering_enable','mi_surround_compressor_steering_enable'):
    show(k[3:30],run('side.f32',pe_hp=V,prof={k:1}),b)
L=dict(volume_leveler_enable=1,volume_leveler_amount=5)
b=run('pinkq.f32',pe_hp=L); show('mi_dv_leveler (señal baja)',run('pinkq.f32',pe_hp=L,prof=dict(mi_dv_leveler_steering_enable=1)),b)
I=dict(ieq_enable=1,selected_ieq_preset_id=2,ieq_amount=16)
b=run('speech.f32',pe_hp=I); show('mi_ieq (voz)',run('speech.f32',pe_hp=I,prof=dict(mi_ieq_steering_enable=1)),b)
b=run('pink.f32',pe_hp=I); show('mi_ieq (ruido)',run('pink.f32',pe_hp=I,prof=dict(mi_ieq_steering_enable=1)),b)
# dependientes del volumen
sh('cmd media_session volume --stream 3 --set 7')
b=run('pink.f32'); show('vol 7 neutro (abs)',b)
show('vol 7 volmax 144',run('pink.f32',pe_hp=dict(volmax_boost=144)),b)
show('vol 7 modelador',run('pink.f32',prof=dict(volume_modeler_enable=1)),b)
show('vol 7 protección aud',run('pink.f32',pe_hp=dict(hearing_protection_enable=1)),b)
sh('cmd media_session volume --stream 3 --set 15')
