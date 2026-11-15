from dbt import *
print(sh("dumpsys audio | grep -A8 '^- STREAM_MUSIC' | grep Devices"))
sh('cmd media_session volume --stream 3 --set 3')
def run(sig,**kw):
    dbset(**kw); return M(sig,warm=3000,secs=3)
try:
    b=run('pink.f32'); show('altavoz neutro (abs)',b)
    bs=M('side.f32',warm=3000,secs=3); show('altavoz side neutro (abs)',bs)
    show('realce bajos (fila altavoz)',run('pink.f32',pe_sp=dict(bass_enhancer_enable=1)),b)
    show('bajo virtual',run('pink.f32',pe_sp=dict(virtual_bass_enable=1)),b)
    show('bajo virtual + realce',run('pink.f32',pe_sp=dict(virtual_bass_enable=1,bass_enhancer_enable=1)),b)
    show('bajo virtual en vivo 112',M('pink.f32',set='112:1',warm=3000,secs=3),b)
    for sb in (0,96): show(f'side virtualiz amplitud {sb} (abs)',run('side.f32',pe_sp=dict(virtualizer_enable=1,surround_boost=sb)))
    show('pink virtualiz altavoz DB',run('pink.f32',pe_sp=dict(virtualizer_enable=1)),b)
    show('pink virtualiz altavoz en vivo 102',M('pink.f32',set='102:1',warm=3000,secs=3),b)
    bq=run('pinkq.f32'); 
    show('volmax 144 señal baja',run('pinkq.f32',pe_sp=dict(volmax_boost=144)),bq)
    show('volmax 144 señal media',run('pink.f32',pe_sp=dict(volmax_boost=144)),b)
    show('protección auditiva',run('pink.f32',pe_sp=dict(hearing_protection_enable=1)),b)
finally:
    install('dax_now.db')
    sh('cmd media_session volume --stream 3 --set 15')
    print(sh('cmd media_session volume --stream 3 --get').splitlines()[-1])
