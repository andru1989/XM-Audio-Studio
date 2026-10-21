# Arquitectura

```
                   ┌──────────────────────── shared/ ────────────────────────┐
                   │ PROTOCOL.md · models.json · fixtures · preset-schema ·  │
                   │ autoeq-test-vectors · design-tokens                     │
                   └───────────────┬───────────────────────────┬────────────┘
                     tests de conformidad                 tests de conformidad
┌──────────────────────── macOS (Swift) ───────┐   ┌──────────────── Android (Kotlin) ──────────────┐
│ XMAudioStudio (SwiftUI)                      │   │ app: Compose UI · MainViewModel · AppSettings  │
│  ├ Dashboard · Estudio EQ · Ajustes · Widget │   │      RfcommControlChannel (BluetoothSocket)    │
│ AudioStudioKit: Biquad · AutoEq · Fitter ·   │   │ core (JVM): protocol/ device/ session/ eq/     │
│                 Profiles                     │   │             presets/                           │
│ SonyHeadphonesKit: Protocol · Commands ·     │   │   HeadphoneSession (ciclo de vida, ACK, cola)  │
│   HeadphonesController · RFCOMMConnection   │   │   ProtocolAdapter → Xm6Adapter                 │
│   (IOBluetooth) · DeviceCatalog · Simulador  │   │   DeviceCatalog · SimulatedXm6Channel          │
└──────────────────────────────────────────────┘   └────────────────────────────────────────────────┘
```

- **Transporte** (RFCOMM) ≠ **audio** (A2DP): la app solo usa el canal de control MDR.
- **Sesión**: una trama en vuelo; adquisición bajo demanda; ver `bluetooth/SESSION-LIFECYCLE.md`.
- **Adaptadores** por generación (ADR-0005); hoy solo XM6.
- **Perfiles**: ilimitados en la app (JSON local), uno activo en los audífonos.
- **EQ**: dos destinos visibles en la UI — EQ de los audífonos (azul) y DSP local (ámbar, planificado).
