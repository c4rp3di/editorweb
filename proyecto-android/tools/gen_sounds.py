#!/usr/bin/env python3
"""Genera los WAV (CC0, síntesis propia) de assets/sound. Sin dependencias."""
import math, random, struct, wave, os
SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'sound')
os.makedirs(OUT, exist_ok=True)

def save(name, samples):
    peak = max(1e-9, max(abs(s) for s in samples))
    with wave.open(os.path.join(OUT, name), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s / peak * 0.9)) * 32767)) for s in samples))

def env(i, n, a=0.005, r=0.3):
    t = i / SR; d = n / SR
    return min(1, t / a) * math.exp(-t / (d * r))

def tone(freqs, dur, decay=0.25, noise=0.0):
    n = int(SR * dur); rnd = random.Random(1)
    out = []
    for i in range(n):
        t = i / SR
        s = sum(math.sin(2 * math.pi * f * t) / (k + 1) for k, f in enumerate(freqs))
        s += noise * (rnd.random() * 2 - 1)
        out.append(s * env(i, n, 0.002, decay))
    return out

save('bounce.wav', tone([1250, 2310, 3470], 0.15, 0.2, 0.15))
save('bump.wav', tone([180, 310], 0.10, 0.3, 0.25))
save('tap.wav', tone([1800], 0.04, 0.4))

# Rodadura: ruido filtrado con modulación periódica, bucle sin cortes (periodo entero)
rnd = random.Random(7); n = SR; lp = 0.0; roll = []
for i in range(n):
    lp += 0.12 * ((rnd.random() * 2 - 1) - lp)
    mod = 0.75 + 0.25 * math.sin(2 * math.pi * 12 * i / n)
    roll.append(lp * mod + 0.15 * math.sin(2 * math.pi * 96 * i / SR))
# suaviza la unión
f = 400
for i in range(f):
    a = i / f; roll[i] = roll[i] * a + roll[n - f + i] * (1 - a) * 0 + roll[i] * 0
save('roll_loop.wav', roll)

# Victoria: arpegio ascendente
notes = [523.25, 659.25, 783.99, 1046.5]; goal = []
for k, fr in enumerate(notes):
    goal += tone([fr, fr * 2], 0.2 if k < 3 else 0.35, 0.5)
save('goal.wav', goal[: int(SR * 0.8)])

# Derrota: descenso
lose = []; 
for k, fr in enumerate([392.0, 329.6, 261.6, 196.0]):
    lose += tone([fr], 0.15 if k < 3 else 0.3, 0.6)
save('lose.wav', lose[: int(SR * 0.6)])
print('Sonidos generados en', os.path.abspath(OUT))
