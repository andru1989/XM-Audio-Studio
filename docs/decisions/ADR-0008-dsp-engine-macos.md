# ADR-0008 — Motor DSP de macOS (propuesta)

Fecha: 2026-10-21 · Estado: Propuesta (Etapa 5)

## Propuesta
Basarlo en ParaEQ (MIT): captura con **Core Audio process taps** (macOS 14.4+) excluyendo el propio
proceso, agregado privado con la salida real como subdispositivo, biquads con `vDSP_biquadm`,
preamplificación automática y limitador con lookahead. Sin driver virtual y **sin cambiar la salida
por defecto**.

Reutilizables tal cual (con aviso MIT): `BiquadEQ.swift`, `Limiter.swift`, `FrequencyResponse.swift`,
`Convolution.swift`, `Spectrum.swift`. `AudioEngine.swift` solo como referencia de diseño.

## Reglas
- Activación/desactivación completa; bypass A/B; nunca aplicar a otro dispositivo de salida que el elegido.
- Las funciones de control XM6 no dependen del motor DSP.
- **Evitar doble corrección**: si el EQ interno ya tiene un perfil AutoEQ, la UI avisa/impide aplicar
  el mismo perfil en DSP.
- Permiso "Grabación de audio del sistema" y entitlement `com.apple.security.device.audio-input`.

## Riesgos (de ParaEQ `docs/ARCHITECTURE.md`)
Bucle de realimentación si un proceso auxiliar nuestro reproduce audio; silencio si el motor se
bloquea; reinicio forzado cuando el XM6 cambia de perfil Bluetooth (A2DP ↔ HFP).
