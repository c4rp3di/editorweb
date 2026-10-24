#!/usr/bin/env python3
"""Genera assets/levels/*.json con el generador procedural (hélices, espirales, zigzag, bifurcaciones)
y valida la geometría con las mismas reglas que LevelValidator.kt (solapes, contención, accesibilidad)."""
import json, math, os, random, sys

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'levels')
os.makedirs(OUT, exist_ok=True)
R_SHELL, R_TUBE, R_BALL = 1.0, 0.07, 0.04
TWO_PI = 2 * math.pi

def place(c, axis, u, a, b):
    if axis == 0: return (c[0] + u, c[1] + a, c[2] + b)
    if axis == 1: return (c[0] + b, c[1] + u, c[2] + a)
    return (c[0] + a, c[1] + b, c[2] + u)

def helix(c, axis, radius, length, turns, ppt, start_angle=0.0, ccw=True, from_end=False, r_end=None):
    n = max(2, int(turns * ppt)); d = 1 if ccw else -1; out = []
    for i in range(n + 1):
        t = i / n
        u = (0.5 - t if from_end else -0.5 + t) * length
        th = start_angle + d * TWO_PI * turns * t
        r = radius if r_end is None else radius + (r_end - radius) * t
        out.append(place(c, axis, u, r * math.cos(th), r * math.sin(th)))
    return out

def spiral(c, axis, r0, r1, turns, offset, ppt=28, start_angle=0.0, ccw=True):
    n = max(2, int(turns * ppt)); d = 1 if ccw else -1; out = []
    for i in range(n + 1):
        t = i / n; r = r0 + (r1 - r0) * t; th = start_angle + d * TWO_PI * turns * t
        out.append(place(c, axis, offset, r * math.cos(th), r * math.sin(th)))
    return out

def sub(a, b): return (a[0]-b[0], a[1]-b[1], a[2]-b[2])
def add(a, b): return (a[0]+b[0], a[1]+b[1], a[2]+b[2])
def mul(a, s): return (a[0]*s, a[1]*s, a[2]*s)
def dot(a, b): return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
def norm(a): return math.sqrt(dot(a, a))
def clamp01(v): return 0.0 if v < 0 else 1.0 if v > 1 else v

def seg_dist(p1, q1, p2, q2):
    d1, d2, r = sub(q1, p1), sub(q2, p2), sub(p1, p2)
    a, e, f = dot(d1, d1), dot(d2, d2), dot(d2, r); eps = 1e-9
    if a <= eps and e <= eps: return norm(r)
    if a <= eps: s = 0.0; t = clamp01(f / e)
    else:
        c = dot(d1, r)
        if e <= eps: t = 0.0; s = clamp01(-c / a)
        else:
            b = dot(d1, d2); den = a * e - b * b
            s = clamp01((b * f - c * e) / den) if den > eps else 0.0
            t = (b * s + f) / e
            if t < 0: t = 0.0; s = clamp01(-c / a)
            elif t > 1: t = 1.0; s = clamp01((b - c) / a)
    return norm(sub(add(p1, mul(d1, s)), add(p2, mul(d2, t))))

def chains_of(level):
    """Lista de (chain_id, idx, a, b) para cada tubo, como en LevelFormat.kt."""
    segs = []; cid = 0
    def add_chain(pts):
        nonlocal cid
        p = [pts[0]]
        for v in pts[1:]:
            if norm(sub(v, p[-1])) > 1e-5: p.append(v)
        for i in range(len(p) - 1): segs.append((cid, i, p[i], p[i+1]))
        cid += 1
    for el in level['track']:
        if el['type'] == 'polyline':
            add_chain([tuple(x) for x in el['points']])
            for br in el.get('branches', []):
                bp = [tuple(x) for x in br['points']]
                j = tuple(el['points'][-1])
                if norm(sub(bp[0], j)) > 1e-4: bp = [j] + bp
                add_chain(bp)
    return segs

def validate(level):
    errs = []; segs = chains_of(level); tol = 2e-3
    def conn(s1, s2):
        return any(norm(sub(x, y)) < tol for x in (s1[2], s1[3]) for y in (s2[2], s2[3]))
    for s in segs:
        for p in (s[2], s[3]):
            if norm(p) + R_TUBE > R_SHELL - 2 * R_BALL: errs.append(f'fuera de esfera {s[0]}#{s[1]} |p|={norm(p):.3f}'); break
    cnt = 0
    for i in range(len(segs)):
        for j in range(i + 1, len(segs)):
            a, b = segs[i], segs[j]
            if a[0] == b[0] and abs(a[1] - b[1]) <= 3: continue
            if conn(a, b): continue
            d = seg_dist(a[2], a[3], b[2], b[3])
            if d < 2 * R_TUBE - 0.002:
                errs.append(f'solape {a[0]}#{a[1]} y {b[0]}#{b[1]}: {d:.3f}'); cnt += 1
                if cnt > 6: return errs
    def inside(s, p, r):
        d = sub(s[3], s[2]); L = norm(d)
        if L < 1e-9: return False
        u = mul(d, 1 / L); ap = sub(p, s[2]); t = dot(ap, u)
        if t < -0.002 or t > L + 0.002: return False
        rv = sub(ap, mul(u, t)); return norm(rv) <= R_TUBE - r + 0.003
    st = [i for i, s in enumerate(segs) if inside(s, tuple(level['start']), R_BALL)]
    gl = [i for i, s in enumerate(segs) if inside(s, tuple(level['goal']), 0.0)]
    if not st: errs.append('inicio fuera de tubos')
    if not gl: errs.append('meta fuera de tubos')
    if st and gl:
        adj = {i: [] for i in range(len(segs))}
        for i in range(len(segs)):
            for j in range(i + 1, len(segs)):
                if conn(segs[i], segs[j]) or (segs[i][0] == segs[j][0] and abs(segs[i][1] - segs[j][1]) == 1):
                    adj[i].append(j); adj[j].append(i)
        seen = set(st); q = list(st)
        while q:
            u = q.pop()
            for v in adj[u]:
                if v not in seen: seen.add(v); q.append(v)
        if not any(g in seen for g in gl): errs.append('meta inaccesible')
    return errs

def R4(p): return [round(p[0], 4), round(p[1], 4), round(p[2], 4)]

def make(id_, name, desc, order, pts, par, open_=0.0, branches=None, start_idx=1, goal_pt=None, gscale=1.0, extra=None):
    el = {'type': 'polyline', 'points': [R4(p) for p in pts], 'radius': R_TUBE, 'open': open_,
          'material': 'metal', 'capStart': True, 'capEnd': True}
    if branches: el['branches'] = branches
    gp = goal_pt if goal_pt is not None else pts[-1]
    lvl = {'id': id_, 'name': name, 'description': desc, 'order': order,
           'start': R4(pts[start_idx]), 'goal': R4(gp), 'goalRadius': 0.06,
           'shell': {'radius': R_SHELL, 'center': [0, 0, 0]}, 'ball': {'radius': R_BALL, 'mass': 0.01},
           'gravityScale': gscale, 'parTime': par, 'track': [el] + (extra or [])}
    return lvl

def slots(n, ranges, w):
    o = [0.0] * n
    for a, b in ranges:
        for i in range(a, min(b, n)): o[i] = w
    return o

levels = []
Z = (0, 0, 0)

# 1. Iniciación: hélice cerrada a lo largo de X (efecto tornillo al girar el móvil sobre su eje X)
p = helix(Z, 0, 0.45, 1.1, 3, 24)
levels.append(make('nivel_01', 'Iniciación', 'Una hélice cerrada. Gira el móvil sobre su eje horizontal: la bola avanza como en un tornillo.', 1, p, 60))

# 2. Tirabuzón: hélice exterior de ida y hélice interior de vuelta
o = helix(Z, 0, 0.5, 1.2, 2.5, 24)
i_ = helix(Z, 0, 0.24, 1.2, 2.5, 28, start_angle=TWO_PI * 2.5, from_end=True)
levels.append(make('nivel_02', 'Tirabuzón', 'Hélice doble: ida por fuera, vuelta por dentro.', 2, o + i_, 90))

# 3. Espiral doble: dos espirales planas en capas distintas que se cruzan en proyección
a = spiral(Z, 2, 0.78, 0.2, 2.5, -0.14, 28, 0.0, True)
ang = TWO_PI * 2.5
b = spiral(Z, 2, 0.2, 0.78, 2.5, 0.14, 28, ang, False)
pts = a + b
n = len(pts) - 1
levels.append(make('nivel_03', 'Espiral doble', 'Dos espirales que se cruzan en capas distintas. Cuidado con las ranuras exteriores.', 3, pts, 120,
                   slots(n, [(len(a) + 20, len(a) + 26), (len(a) + 38, len(a) + 44)], 0.6)))

# 4. Bifurcación: un tubo que se divide; una rama es un callejón sin salida
main = [(-0.52, 0.52, 0), (-0.38, 0.52, 0), (-0.22, 0.52, 0), (-0.05, 0.52, 0)]
J = main[-1]
dead = [J, (0.20, 0.66, 0), (0.42, 0.68, 0)]
good = [J, (0.20, 0.40, 0), (0.45, 0.30, 0), (0.62, 0.05, 0), (0.55, -0.25, 0), (0.30, -0.45, 0),
        (0.0, -0.5, 0), (-0.3, -0.45, 0), (-0.55, -0.25, 0)]
bo = [0.0] * (len(good) - 1); bo[3] = 0.6; bo[4] = 0.6
l4 = make('nivel_04', 'Bifurcación', 'El tubo se divide en dos: una rama no tiene salida.', 4, main, 100, 0.0,
          branches=[{'points': [R4(x) for x in dead], 'capEnd': True},
                    {'points': [R4(x) for x in good], 'open': bo, 'capEnd': True}],
          goal_pt=good[-1])
l4['track'][0]['capEnd'] = False
levels.append(l4)

# 5. Cascada: tres hélices verticales encadenadas
h1 = helix((-0.55, 0, 0), 1, 0.15, 1.1, 3, 16, math.pi / 2, True, True)
h2 = helix((0.0, 0, 0), 1, 0.15, 1.1, 3.5, 16, -math.pi / 2, True, False)
h3 = helix((0.55, 0, 0), 1, 0.15, 1.1, 3, 16, -math.pi / 2, True, True)
pts = h1 + h2 + h3; n = len(pts) - 1
levels.append(make('nivel_05', 'Cascada', 'Tres hélices verticales encadenadas: baja, sube y vuelve a bajar.', 5, pts, 150,
                   slots(n, [(len(h1) + len(h2) + 10, len(h1) + len(h2) + 14), (len(h1) + len(h2) + 30, len(h1) + len(h2) + 34)], 0.6)))

# 6. Montaña rusa: curva senoidal 3D
pts = []
N = 30
for k in range(N + 1):
    x = -0.72 + 1.44 * k / N
    pts.append((x, 0.32 * math.sin(TWO_PI * x * 0.9), 0.28 * math.sin(TWO_PI * x * 0.55 + 1.0)))
n = len(pts) - 1
levels.append(make('nivel_06', 'Montaña rusa', 'Una pista ondulada con ranuras en las crestas.', 6, pts, 110,
                   slots(n, [(8, 10), (15, 17), (22, 24)], 0.7)))

# 7. Laberinto cúbico: serpiente 3D sobre una rejilla (semilla fija, se busca una ruta válida)
def snake(seed):
    rnd = random.Random(seed); g = [-0.45, -0.15, 0.15, 0.45]
    cur = (0, 0, 0); path = [cur]; vis = {cur}; dirs = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]
    last = None
    for _ in range(40):
        opts = []
        for d in dirs:
            nx = (cur[0]+d[0], cur[1]+d[1], cur[2]+d[2])
            if all(0 <= c < 4 for c in nx) and nx not in vis and d != last: opts.append((d, nx))
        if not opts: break
        d, nx = rnd.choice(opts); cur = nx; last = d; path.append(cur); vis.add(cur)
    return [(g[a], g[b], g[c]) for a, b, c in path]
best = None
for sd in range(500):
    s = snake(sd)
    if len(s) >= 16:
        lv = make('nivel_07', 'Laberinto cúbico', 'Una serpiente tridimensional: planifica los giros.', 7, s, 140,
                  slots(len(s) - 1, [(4, 5), (8, 9), (12, 13)], 0.8))
        if not validate(lv): best = lv; break
if best is None: sys.exit('no se encontró laberinto válido')
levels.append(best)

# 8. Gran final: hélice cónica con ranuras
pts = helix(Z, 2, 0.56, 1.1, 4, 26, 0.0, True, False, r_end=0.30)
n = len(pts) - 1
levels.append(make('nivel_08', 'Gran final', 'Una hélice cónica con ranuras: el reto definitivo.', 8, pts, 180,
                   slots(n, [(20, 23), (45, 48), (70, 73)], 0.6)))

ok = True
for lv in levels:
    e = validate(lv)
    segs = len(chains_of(lv))
    print(f"{lv['id']}: {lv['name']:<18} tubos={segs:3d}  {'OK' if not e else 'ERRORES'}")
    for x in e: print('   ', x)
    if e: ok = False
    with open(os.path.join(OUT, lv['id'].replace('nivel_', 'level_') + '.json'), 'w', encoding='utf-8') as f:
        json.dump(lv, f, ensure_ascii=False, indent=1)
with open(os.path.join(OUT, 'index.json'), 'w') as f:
    json.dump({'levels': [lv['id'].replace('nivel_', 'level_') + '.json' for lv in levels]}, f, indent=1)
sys.exit(0 if ok else 1)
