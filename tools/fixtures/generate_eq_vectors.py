#!/usr/bin/env python3
"""Independent reference for the EQ maths shared by both apps.

Writes shared/autoeq-test-vectors/vectors.json: RBJ biquad magnitudes, parsed AutoEq
profiles and fixed-band fits. The Swift (AudioStudioKit) and Kotlin (core/eq) ports are
both tested against this output. Profiles here are synthetic: AutoEq's measurement data
carries no licence, so none of it is stored in this repository.

Usage: python3 tools/fixtures/generate_eq_vectors.py
"""
import json
import math
import os

FS = 48000.0


def coefficients(ftype, f0, gain, q, fs=FS):
    a = 10 ** (gain / 40.0)
    w0 = 2 * math.pi * f0 / fs
    cw = math.cos(w0)
    alpha = math.sin(w0) / (2 * q)
    if ftype == "peaking":
        b0, b1, b2 = 1 + alpha * a, -2 * cw, 1 - alpha * a
        a0, a1, a2 = 1 + alpha / a, -2 * cw, 1 - alpha / a
    elif ftype == "lowShelf":
        k = 2 * math.sqrt(a) * alpha
        b0 = a * ((a + 1) - (a - 1) * cw + k)
        b1 = 2 * a * ((a - 1) - (a + 1) * cw)
        b2 = a * ((a + 1) - (a - 1) * cw - k)
        a0 = (a + 1) + (a - 1) * cw + k
        a1 = -2 * ((a - 1) + (a + 1) * cw)
        a2 = (a + 1) + (a - 1) * cw - k
    else:  # highShelf
        k = 2 * math.sqrt(a) * alpha
        b0 = a * ((a + 1) + (a - 1) * cw + k)
        b1 = -2 * a * ((a - 1) + (a + 1) * cw)
        b2 = a * ((a + 1) + (a - 1) * cw - k)
        a0 = (a + 1) - (a - 1) * cw + k
        a1 = 2 * ((a - 1) - (a + 1) * cw)
        a2 = (a + 1) - (a - 1) * cw - k
    return b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0


def magnitude_db(c, f, fs=FS):
    b0, b1, b2, a1, a2 = c
    w = 2 * math.pi * f / fs
    num = complex(b0 + b1 * math.cos(w) + b2 * math.cos(2 * w), -(b1 * math.sin(w) + b2 * math.sin(2 * w)))
    den = complex(1 + a1 * math.cos(w) + a2 * math.cos(2 * w), -(a1 * math.sin(w) + a2 * math.sin(2 * w)))
    return 10 * math.log10(max(abs(num) ** 2, 1e-30) / max(abs(den) ** 2, 1e-30))


def response(filters, freqs):
    cs = [coefficients(t, f, g, q) for t, f, g, q in filters]
    return [sum(magnitude_db(c, f) for c in cs) for f in freqs]


def grid(lo=20.0, hi=20000.0, n=240):
    r = math.log10(hi / lo)
    return [lo * 10 ** (r * i / (n - 1)) for i in range(n)]


def weight(f):
    if f < 40:
        return 0.5
    if f <= 6000:
        return 1.0
    if f >= 10000:
        return 0.3
    return 1.0 - 0.7 * (math.log10(f / 6000) / math.log10(10000 / 6000))


def fit(model, target, freqs, lam=0.02):
    n, bands = len(freqs), len(model["bands"])
    w = [weight(f) for f in freqs]
    wsum = sum(w)
    basis = [response([("peaking", fc, 1.0, model["q"])], freqs) for fc in model["bands"]]
    lo, hi = model["minStep"] * model["stepDb"], model["maxStep"] * model["stepDb"]
    x = [0.0] * bands
    offset = 0.0
    cur = [0.0] * n
    for _ in range(500):
        change = 0.0
        new_offset = sum(w[i] * (target[i] - cur[i]) for i in range(n)) / wsum
        change = max(change, abs(new_offset - offset))
        offset = new_offset
        for k in range(bands):
            b = basis[k]
            num, den = 0.0, lam
            for i in range(n):
                partial = cur[i] - x[k] * b[i]
                num += w[i] * b[i] * (target[i] - offset - partial)
                den += w[i] * b[i] * b[i]
            upd = min(max(num / den, lo), hi)
            d = upd - x[k]
            if d != 0.0:
                for i in range(n):
                    cur[i] += d * b[i]
                x[k] = upd
            change = max(change, abs(d))
        if change < 1e-10:
            break

    def predicted(steps):
        return response([("peaking", fc, s * model["stepDb"], model["q"]) for fc, s in zip(model["bands"], steps)], freqs)

    def wmean(t, p):
        return sum(w[i] * (t[i] - p[i]) for i in range(n)) / wsum

    def objective(steps):
        p = predicted(steps)
        o = wmean(target, p)
        s = sum(w[i] * (p[i] + o - target[i]) ** 2 for i in range(n))
        return s + sum(lam * (st * model["stepDb"]) ** 2 for st in steps)

    steps = [min(max(int(math.floor(v / model["stepDb"] + 0.5)), model["minStep"]), model["maxStep"]) for v in x]
    best = objective(steps)
    improved, passes = True, 0
    while improved and passes < 100:
        improved, passes = False, passes + 1
        for k in range(bands):
            for d in (-1, 1):
                cand = steps[k] + d
                if cand < model["minStep"] or cand > model["maxStep"]:
                    continue
                orig = steps[k]
                steps[k] = cand
                v = objective(steps)
                if v < best - 1e-12:
                    best, improved = v, True
                else:
                    steps[k] = orig
    p = predicted(steps)
    o = wmean(target, p)
    rms = math.sqrt(sum(w[i] * (p[i] + o - target[i]) ** 2 for i in range(n)) / wsum)
    return {"steps": steps, "continuousGainsDb": x, "levelOffsetDb": o, "weightedRmsErrorDb": rms}


XM6_MODEL = {
    "bands": [31.0, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0],
    "q": 1.41, "stepDb": 1.0, "minStep": -6, "maxStep": 6,
}

PROFILES = {
    "synthetic-v-shape": """Preamp: -4.0 dB
Filter 1: ON LSC Fc 105 Hz Gain 4.0 dB Q 0.70
Filter 2: ON PK Fc 3000 Hz Gain -3.0 dB Q 1.20
Filter 3: OFF PK Fc 5000 Hz Gain 9.0 dB Q 2.00
Filter 4: ON HSC Fc 10000 Hz Gain 3.0 dB Q 0.70
""",
    "synthetic-mid-dip": """Preamp: -2.1 dB
Filter 1: ON PK Fc 250 Hz Gain 2.0 dB Q 0.90
Filter 2: ON PK Fc 1000 Hz Gain -4.0 dB Q 1.00
Filter 3: ON PK Fc 6500 Hz Gain 1.5 dB Q 3.00
""",
}

TYPE_CODES = {"PK": "peaking", "LSC": "lowShelf", "HSC": "highShelf"}


def parse(text):
    preamp, filters = 0.0, []
    for line in text.splitlines():
        parts = line.split()
        if line.startswith("Preamp:"):
            preamp = float(parts[1])
        elif line.startswith("Filter") and parts[2] == "ON":
            filters.append((TYPE_CODES[parts[3]], float(parts[5]), float(parts[8]), float(parts[11])))
    return preamp, filters


def main():
    probe = [20.0, 50.0, 100.0, 1000.0, 3000.0, 10000.0, 16000.0]
    biquads = []
    for t, f0, g, q in [("peaking", 1000.0, 6.0, 1.41), ("peaking", 63.0, -4.5, 0.7),
                        ("lowShelf", 105.0, 4.0, 0.7), ("highShelf", 10000.0, -3.0, 0.7)]:
        biquads.append({"type": t, "frequency": f0, "gainDb": g, "q": q, "sampleRate": FS,
                        "coefficients": list(coefficients(t, f0, g, q)),
                        "probeHz": probe, "magnitudeDb": [magnitude_db(coefficients(t, f0, g, q), f) for f in probe]})

    freqs = grid()
    fits = []
    for name, text in PROFILES.items():
        preamp, filters = parse(text)
        target = response(filters, freqs)
        result = fit(XM6_MODEL, target, freqs)
        fits.append({"name": name, "parametricText": text,
                     "parsed": {"preampDb": preamp, "filters": [
                         {"type": t, "frequency": f, "gainDb": g, "q": q} for t, f, g, q in filters]},
                     "expected": result})

    exact_steps = [2, 1, 0, -1, -2, 0, 1, 2, 3, -3]
    exact_target = response([("peaking", fc, s * 1.0, XM6_MODEL["q"]) for fc, s in zip(XM6_MODEL["bands"], exact_steps)], freqs)
    exact = fit(XM6_MODEL, exact_target, freqs)
    assert exact["steps"] == exact_steps, exact["steps"]
    fits.append({"name": "exactly-representable", "deviceSteps": exact_steps, "expected": exact})

    out = {
        "schema": "xm-audio-studio.eq-vectors/1",
        "generator": "tools/fixtures/generate_eq_vectors.py",
        "tolerances": {"magnitudeDb": 1e-9, "coefficient": 1e-12, "fitContinuousDb": 1e-6, "fitRmsDb": 1e-6},
        "grid": {"minHz": 20.0, "maxHz": 20000.0, "points": 240},
        "deviceModel": XM6_MODEL,
        "biquads": biquads,
        "fits": fits,
    }
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(root, "shared", "autoeq-test-vectors", "vectors.json")
    with open(path, "w") as f:
        json.dump(out, f, indent=2)
        f.write("\n")
    for fit_case in fits:
        print(fit_case["name"], fit_case["expected"]["steps"], round(fit_case["expected"]["weightedRmsErrorDb"], 3))


if __name__ == "__main__":
    main()
