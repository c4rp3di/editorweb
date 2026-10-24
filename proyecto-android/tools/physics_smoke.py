#!/usr/bin/env python3
"""Port fiel (simplificado) de la física Kotlin para comprobar el diseño: bola dentro de tubos huecos,
solver de impulsos con fricción, marco no inercial. Gira el 'móvil' alrededor de X y comprueba el efecto tornillo."""
import json, math, sys, os
def sub(a,b): return [a[0]-b[0],a[1]-b[1],a[2]-b[2]]
def add(a,b): return [a[0]+b[0],a[1]+b[1],a[2]+b[2]]
def mul(a,s): return [a[0]*s,a[1]*s,a[2]*s]
def dot(a,b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def cross(a,b): return [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
def norm(a): return math.sqrt(dot(a,a))
def unit(a):
    l=norm(a); return [a[0]/l,a[1]/l,a[2]/l] if l>1e-12 else [0,0,0]

class Tube:
    def __init__(s,a,b,R,open_,capA,capB):
        s.a,s.b,s.R,s.open=a,b,R,open_; s.capA,s.capB=capA,capB
        d=sub(b,a); s.len=norm(d); s.u=unit(d)
class Joint:
    def __init__(s,c,R,din,douts): s.c,s.R,s.din,s.douts=c,R,din,douts

def load(path):
    lv=json.load(open(path)); tubes=[]; joints=[]
    for el in lv['track']:
        if el['type']!='polyline': continue
        pts=[p for p in el['points']]; op=el.get('open',0)
        for i in range(len(pts)-1):
            o=op[i] if isinstance(op,list) else op
            tubes.append(Tube(pts[i],pts[i+1],el['radius'],o,el.get('capStart',False) and i==0,el.get('capEnd',False) and i==len(pts)-2))
        for i in range(1,len(pts)-1):
            din=unit(sub(pts[i],pts[i-1])); dout=unit(sub(pts[i+1],pts[i]))
            if math.degrees(math.acos(max(-1,min(1,dot(din,dout)))))>4: joints.append(Joint(pts[i],el['radius'],din,[dout]))
    return lv,tubes,joints

def rot(v,a,ang):
    c,s=math.cos(ang),math.sin(ang); k=cross(a,v)
    return add(add(mul(v,c),mul(k,s)),mul(a,dot(a,v)*(1-c)))
def slot_dir(t,s):
    c=add(t.a,mul(t.u,s)); d=dot(c,t.u); w=sub(c,mul(t.u,d)); l=norm(w)
    return unit(w) if l>1e-4 else unit(cross(t.u,[0,1,0]))

def contacts(p,r,tubes,joints,shellR):
    out=[]
    for t in tubes:
        ap=sub(p,t.a); s=dot(ap,t.u)
        rv0=sub(ap,mul(t.u,s))
        if norm(rv0)<t.R:
            if t.capA and -t.R<s<r: out.append((t.u,r-s,False))
            if t.capB and t.len-r<s<t.len+t.R: out.append((mul(t.u,-1),s-(t.len-r),False))
        if s<0 or s>t.len: continue
        rv=sub(ap,mul(t.u,s)); rd=norm(rv); lim=t.R-r
        if lim<=0 or rd<=lim or rd>=t.R: continue
        n=mul(rv,1/rd)
        if t.open>0:
            eff=t.open-r/t.R
            if eff>0 and dot(n,slot_dir(t,s))>math.cos(eff): continue
        out.append((mul(n,-1),rd+r-t.R,False))
    for j in joints:
        d=sub(p,j.c)
        if dot(d,j.din)<=0 or any(dot(d,o)>=0 for o in j.douts): continue
        dd=norm(d); lim=j.R-r
        if lim<=0 or dd<=lim or dd>=j.R: continue
        out.append((mul(d,-1/dd),dd+r-j.R,False))
    dd=norm(p)
    if dd+r>shellR: out.append((mul(p,-1/dd),dd+r-shellR,True))
    return out

def simulate(level,omega_x,T=30.0,verbose=False,axis=(1,0,0),up0=(0,1,0)):
    lv,tubes,joints=load(level); r=lv['ball']['radius']; m=lv['ball']['mass']
    I=0.4*m*r*r; g=9.81; dt=1/240
    p=list(lv['start']); v=[0,0,0]; w=[0,0,0]; goal=lv['goal']; gr=lv['goalRadius']
    th=0.0; best=1e9; maxspeed=0; status='timeout'
    for step in range(int(T/dt)):
        th+=omega_x*dt
        # gravedad en el sistema del dispositivo girado alrededor de X: up_body = Rx(-th)·(0,1,0)
        up=rot(up0,axis,-th)
        gE=mul(up,-g); om=[axis[0]*omega_x,axis[1]*omega_x,axis[2]*omega_x]
        # fuerzas: gravedad + centrifuga + coriolis
        a=add(gE, mul(cross(om,cross(om,p)),-1)); a=add(a,mul(cross(om,v),-2))
        v=add(v,mul(a,dt)); v=mul(v,1-0.05*dt); w=mul(w,1-0.1*dt)
        p=add(p,mul(v,dt))
        cs=contacts(p,r,tubes,joints,lv['shell']['radius'])
        if cs:
            data=[]
            for n,pen,leth in cs:
                if leth: return 'LOST (shell)',step*dt,best,maxspeed
                rc=mul(n,-r); vr=add(v,cross(w,rc)); vn=dot(vr,n)
                bias=-0.65*vn if vn<-0.6 else 0
                t1=unit(cross(n,[1,0,0])) if abs(n[0])<0.9 else unit(cross(n,[0,1,0])); t2=cross(n,t1)
                data.append([n,pen,bias,t1,t2,0.0,0.0,0.0])
            invM=1/m; invI=1/I; tm=1/(invM+r*r*invI); mu_s,mu_d=0.55,0.4
            for it in range(8):
                for d in data:
                    n,pen,bias,t1,t2=d[0],d[1],d[2],d[3],d[4]
                    rc=mul(n,-r); vr=add(v,cross(w,rc))
                    dj=-(dot(vr,n)-bias)*m; nj=max(0,d[5]+dj); dj=nj-d[5]; d[5]=nj
                    v=add(v,mul(n,dj*invM))
                    vr=add(v,cross(w,rc))
                    j1=d[6]-dot(vr,t1)*tm; j2=d[7]-dot(vr,t2)*tm
                    ln=math.hypot(j1,j2)
                    if ln>mu_s*d[5]:
                        k=mu_d*d[5]/ln if ln>1e-12 else 0; j1*=k; j2*=k
                    d1=j1-d[6]; d2=j2-d[7]; d[6]=j1; d[7]=j2
                    P=add(mul(t1,d1),mul(t2,d2)); v=add(v,mul(P,invM)); w=add(w,mul(cross(rc,P),invI))
            corr=[0,0,0]
            for d in data:
                pen=d[1]-dot(corr,d[0])
                if pen>0.0004: corr=add(corr,mul(d[0],min(0.2*(pen-0.0004),0.02)))
            p=add(p,corr)
            for d in data:   # rodadura
                if d[5]>0:
                    n=d[0]; wn=dot(w,n); tw=sub(w,mul(n,wn)); tl=norm(tw)
                    if tl>1e-6:
                        md=0.005*d[5]*r/I; k=min(md,tl)/tl; w=sub(w,mul(tw,k))
        sp=norm(v); maxspeed=max(maxspeed,sp)
        if not all(math.isfinite(x) for x in p+v+w): return 'NaN',step*dt,best,maxspeed
        dg=norm(sub(p,goal)); best=min(best,dg)
        if dg<=gr: return 'WON',step*dt,best,maxspeed
    return status,T,best,maxspeed

if __name__=='__main__':
    base=os.path.join(os.path.dirname(__file__),'..','app','src','main','assets','levels')
    for om in (+1.2,-1.2,+2.5,-2.5):
        print('nivel_01 omega_x=%+.1f ->'%om, simulate(os.path.join(base,'level_01.json'),om,40))
