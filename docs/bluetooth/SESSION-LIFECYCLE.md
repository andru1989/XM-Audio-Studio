# Ciclo de vida de la sesión de control MDR

Decisión: [ADR-0003](../decisions/ADR-0003-on-demand-control-channel.md). Implementaciones:
`android/core/.../session/HeadphoneSession.kt` (estado + tests en `HeadphoneSessionTest.kt`) y
`macos/Sources/SonyHeadphonesKit/HeadphonesController.swift` + `ConnectionUsage.swift` (Anneo22).

```
            superficie visible / comando del usuario
IDLE ─────────────────────────────────────────────▶ CONNECTING ──(8 s, 1 reintento)──▶ FAILED
  ▲                                                     │ canal abierto
  │ 20 s sin superficies ni comandos                    ▼
  │                                                INITIALIZING ──(init 00 00, 2 reintentos)──▶ FAILED
  │                                                     │ 01 … (v2)        └─ v1 inesperado ─▶ FAILED (sin escribir)
  │                                                     ▼
  │                                                READING_STATE  (04 01, 04 02, 66 19, 22 00, 56 04, …)
  │                                                     │ cola vacía + 300 ms (o 6 s)
  │                                                     ▼
  └──────────────────────────────────────────────── CONNECTED ──▶ intenciones pendientes codificadas contra el estado fresco
                                                        │
                       los audífonos cierran el canal   │ sin trabajo pendiente
                                                        ▼
                                                    YIELDED (no reconecta hasta actividad del usuario)
```

Reglas:
- Una sola trama en vuelo (stop-and-wait); timeout de ACK 3 s.
- Intenciones del mismo tipo se fusionan mientras no se envían (limita la tasa al arrastrar el EQ).
- Fallo al conectar → se descartan intenciones y se revierte el estado optimista.
- "Desconectar" libera de inmediato; una superficie que sigue visible no vuelve a adquirir por sí sola.
