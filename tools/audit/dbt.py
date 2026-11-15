from base import *
import sqlite3, shutil, subprocess
def install(path):
    subprocess.run([ADB,'push',path,'/data/local/tmp/dax_t.db'],capture_output=True)
    sh('DB=/data/vendor/dolby/dax_sqlite3.db; setprop ctl.stop dms-hal; sleep 1; cat /data/local/tmp/dax_t.db > $DB.new && mv -f $DB.new $DB && rm -f $DB-journal; chown media:audio $DB; chmod 600 $DB; chcon u:object_r:vendor_data_file:s0 $DB; setprop ctl.start dms-hal; sleep 1; killall audioserver; sleep 4',su=True)
PE_OFF=dict(bass_enhancer_enable=0,virtual_bass_enable=0,ieq_enable=0,dialog_enhancer_enable=0,dialog_enhancer_ducking=0,virtualizer_enable=0,
  volume_leveler_enable=0,hearing_protection_enable=0,volmax_boost=0,surround_boost=0,graphic_equalizer_enable=1)
PR_OFF=dict(mi_adaptive_virtualizer_steering_enable=0,mi_dv_leveler_steering_enable=0,mi_ieq_steering_enable=0,
  mi_surround_compressor_steering_enable=0,reverb_suppression_enable=0,volume_modeler_enable=0)
def dbset(pe_hp={},pe_sp={},prof={},tun_hp={}):
    shutil.copy('dax_now.db','t.db'); c=sqlite3.connect('t.db')
    for ep,extra in (('headphone',pe_hp),('speaker',pe_sp)):
        for k,v in {**PE_OFF,**extra}.items(): c.execute(f"update profile_endpoints set {k}=? where profile_id='2' and endpoint=?",(str(v),ep))
    for k,v in {**PR_OFF,**prof}.items(): c.execute(f"update profiles set {k}=? where profile_id='2'",(str(v),))
    c.execute("update geq_presets set geq_gain=? where profile_id='2'",(','.join(['0']*20),))
    for k,v in tun_hp.items(): c.execute(f"update tunings set {k}=? where endpoint='headphone'",(str(v),))
    c.commit(); c.close(); install('t.db')
def low(r,q=0.2):  # level of the quietest frames (gaps / tails)
    s=np.sort(r['rms']); return 20*np.log10(np.mean(s[:max(1,int(len(s)*q))])+1e-9)
