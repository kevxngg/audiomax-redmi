from dbt import *
print(sh("dumpsys audio | grep -A8 '^- STREAM_MUSIC' | grep Devices"))
sh('cmd media_session volume --stream 3 --set 3')
def run(sig,**kw):
    dbset(**kw); return M(sig,warm=3000,secs=3)
try:
    b=run('pink.f32'); show('speaker neutral (abs)',b)
    bs=M('side.f32',warm=3000,secs=3); show('speaker side neutral (abs)',bs)
    show('bass enhancer (speaker row)',run('pink.f32',pe_sp=dict(bass_enhancer_enable=1)),b)
    show('virtual bass',run('pink.f32',pe_sp=dict(virtual_bass_enable=1)),b)
    show('virtual bass + enhancer',run('pink.f32',pe_sp=dict(virtual_bass_enable=1,bass_enhancer_enable=1)),b)
    show('virtual bass live 112',M('pink.f32',set='112:1',warm=3000,secs=3),b)
    for sb in (0,96): show(f'side virtualizer width {sb} (abs)',run('side.f32',pe_sp=dict(virtualizer_enable=1,surround_boost=sb)))
    show('pink speaker virtualizer DB',run('pink.f32',pe_sp=dict(virtualizer_enable=1)),b)
    show('pink speaker virtualizer live 102',M('pink.f32',set='102:1',warm=3000,secs=3),b)
    bq=run('pinkq.f32'); 
    show('volmax 144 quiet signal',run('pinkq.f32',pe_sp=dict(volmax_boost=144)),bq)
    show('volmax 144 medium signal',run('pink.f32',pe_sp=dict(volmax_boost=144)),b)
    show('hearing protection',run('pink.f32',pe_sp=dict(hearing_protection_enable=1)),b)
finally:
    install('dax_now.db')
    sh('cmd media_session volume --stream 3 --set 15')
    print(sh('cmd media_session volume --stream 3 --get').splitlines()[-1])
