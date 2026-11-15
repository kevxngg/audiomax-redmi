from m import *
Z=','.join(['0']*20)
NEUTRAL=f"101:0;102:0;103:0;104:0;105:0;110:{Z};111:0;112:0"
def M(sig='pink.f32',set=None,warm=2500,secs=4): return measure(sig,set=set,warm=warm,secs=secs)
