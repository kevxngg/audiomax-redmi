import numpy as np
SR=48000; N=SR*10; rng=np.random.default_rng(1)
def pink(n):
    X=np.fft.rfft(rng.standard_normal(n)); f=np.arange(len(X)); f[0]=1
    fr=f*SR/n; X/=np.sqrt(f); X[fr<40]=0; X[fr>20000]=0; x=np.fft.irfft(X,n); return x/np.sqrt(np.mean(x**2))
def db(d): return 10**(d/20)
def save(name,L,R):
    s=np.stack([L,R],1).astype('<f4'); assert np.abs(s).max()<1, (name,np.abs(s).max()); s.tofile('sig/'+name)
def biquad_res(x,f,bw):
    r=np.exp(-np.pi*bw/SR); th=2*np.pi*f/SR
    a1=-2*r*np.cos(th); a2=r*r; y=np.zeros_like(x); y1=y2=0.0
    g=(1-r)
    for i in range(len(x)):
        v=g*x[i]-a1*y1-a2*y2; y[i]=v; y2=y1; y1=v
    return y
def speech(n):
    out=np.zeros(n); t=0
    vowels=[(730,1090,2440),(270,2290,3010),(300,870,2240),(530,1840,2480),(570,840,2410)]
    while t<n-SR//2:
        L=int(SR*rng.uniform(.15,.28)); f0=rng.uniform(100,150)
        ph=np.cumsum(np.full(L,f0*(1+0.03*np.sin(np.arange(L)/SR*2*np.pi*5)))/SR)
        src=np.diff(np.floor(ph),prepend=0)*1.0
        F=vowels[rng.integers(len(vowels))]
        v=sum(biquad_res(src,f,80+f*0.06)*(1/(k+1)) for k,f in enumerate(F))
        env=np.sin(np.linspace(0,np.pi,L))**0.6; seg=v*env
        if rng.random()<0.3:  # fricative
            fr=np.diff(rng.standard_normal(L//2),prepend=0)*0.3*np.hanning(L//2)
            seg=np.concatenate([fr,seg]); L=len(seg)
        out[t:t+L]+=seg[:n-t]; t+=L+int(SR*rng.uniform(.05,.18))
    return out/np.sqrt(np.mean(out[out!=0]**2))
p1,p2=pink(N),pink(N)
save('pink.f32',p1*db(-20),p2*db(-20))
save('side.f32',p1*db(-20),-p1*db(-20))
sp=speech(N); open('sig/speech_mask.npy','wb').write(np.abs(sp).tobytes()[:0])
sp=sp/np.abs(sp).max()*0.45
save('speech.f32',sp+p1*db(-34),sp+p2*db(-34))
g=np.where((np.arange(N)//SR)%2==0,db(-16),db(-40)); g=np.convolve(g,np.ones(480)/480,'same')
save('dyn.f32',p1*g*0.9,p2*g*0.9)
ir=rng.standard_normal(int(SR*1.2))*np.exp(-np.arange(int(SR*1.2))/SR*6.9/1.0)
rv=np.fft.irfft(np.fft.rfft(sp,N+len(ir))*np.fft.rfft(ir,N+len(ir)))[:N]; rv/=np.sqrt(np.mean(rv**2))
wet=sp+rv*db(-6); wet/=np.abs(wet).max()/0.5
save('reverb.f32',wet,wet)
print('ok')
save('pinkq.f32',p1*db(-40),p2*db(-40))
save('pinkl.f32',p1*db(-15)/1.3*1.0,p2*db(-15)/1.3)
