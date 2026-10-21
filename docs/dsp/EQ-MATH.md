# Matemáticas de EQ compartidas

Implementadas en `android/core/.../eq/Biquad.kt` y `macos/Sources/AudioStudioKit/Biquad.swift`;
fijadas por `shared/autoeq-test-vectors/vectors.json` (referencia Python independiente,
`tools/fixtures/generate_eq_vectors.py`). Tolerancias: coeficientes 1e‑12, magnitudes 1e‑9 dB.

## Biquads RBJ (Audio EQ Cookbook), fs = 48 kHz
A = 10^(G/40), w0 = 2π·f0/fs, α = sin(w0)/(2Q)

| Tipo | b0 | b1 | b2 | a0 | a1 | a2 |
|---|---|---|---|---|---|---|
| Peaking | 1+αA | −2cos w0 | 1−αA | 1+α/A | −2cos w0 | 1−α/A |
| Low shelf | A[(A+1)−(A−1)cos w0+2√Aα] | 2A[(A−1)−(A+1)cos w0] | A[(A+1)−(A−1)cos w0−2√Aα] | (A+1)+(A−1)cos w0+2√Aα | −2[(A−1)+(A+1)cos w0] | (A+1)+(A−1)cos w0−2√Aα |
| High shelf | A[(A+1)+(A−1)cos w0+2√Aα] | −2A[(A−1)+(A+1)cos w0] | A[(A+1)+(A−1)cos w0−2√Aα] | (A+1)−(A−1)cos w0+2√Aα | 2[(A−1)−(A+1)cos w0] | (A+1)−(A−1)cos w0−2√Aα |

Todos normalizados por a0. Magnitud: |H(e^jw)|² = |b0+b1e^−jw+b2e^−2jw|² / |1+a1e^−jw+a2e^−2jw|², en dB = 10·log10.

## Modelo del EQ de los audífonos
Cada banda = peaking a la frecuencia de banda, Q = 1,41, ganancia = paso × 1 dB (supuesto).
Es una **predicción**: Sony no publica la forma real de sus filtros.
