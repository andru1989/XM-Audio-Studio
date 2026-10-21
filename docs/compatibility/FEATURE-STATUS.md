# Estado de funciones

Actualizado: 2026-10-21. Niveles (encargo §15):

| Nivel | Significado |
|---|---|
| **HW** | Implementada y probada con hardware real **en este proyecto** |
| **HW‑up** | Byte layout confirmado en un XM6 real por upstream/terceros (captura citada); nuestra app aún no se probó con hardware |
| **SIM** | Implementada y probada con simulación (XM6 simulado a nivel de bytes, fixtures compartidos) |
| **PEND** | Implementada, pendiente de pruebas reales |
| **EXP** | Experimental |
| **PLAN** | Pendiente |
| **N/C** | No compatible |

> Ninguna función tiene todavía nivel **HW**: no se ha conectado un XM6 real a esta versión.
> Siguiente paso: [`docs/testing/HARDWARE-TEST-PLAN.md`](../testing/HARDWARE-TEST-PLAN.md).

## WH-1000XM6

| Función | Protocolo | macOS | Android |
|---|---|---|---|
| Conexión RFCOMM (UUID v2, SDP) | HW‑up | PEND (código upstream en uso real) | PEND (transporte propio) |
| Canal bajo demanda, liberación 20 s, cesión | — | SIM (32 tests de Anneo22) | SIM (14 tests de sesión) |
| Leer estado real antes de escribir tras reconectar | — | PLAN (PR #4 envía comandos primero) | SIM |
| Identificación (modelo `04 01`, firmware `04 02`) | REF | SIM | SIM |
| Batería y carga (tipo 00 y 08) | HW‑up (00) | SIM | SIM |
| ANC / ambiente / nivel (0x19 lectura, 0x17 escritura) | HW‑up | SIM | SIM |
| Enfocar en la voz | PEND | SIM | SIM |
| Modo de escucha (Estándar/BGM/Cine) + sala | PEND | SIM | SIM |
| Hablar para chatear + configuración | PEND | SIM | SIM |
| Pausa al quitarse | HW‑up | SIM | SIM |
| Apagado automático (10/11) | HW‑up | SIM | SIM |
| Lista multipunto | HW‑up | SIM | SIM |
| Cambiar fuente de reproducción | PEND | SIM | SIM |
| EQ presets Sony (00, 30–33) | HW‑up | SIM | SIM |
| EQ 10 bandas −6…+6 | HW‑up | SIM | SIM |
| Capacidad EQ `50 04 01` (bandas/pasos) | REF | SIM (solo lectura) | SIM (solo lectura) |
| Perfiles ilimitados, duplicar, renombrar, borrar | — | SIM (tests) | SIM (tests) |
| Exportar / importar perfiles (formato compartido) | — | SIM | SIM |
| Deshacer, A/B | — | SIM | SIM (emulador) |
| Guardar en la app la curva que ya tienen los audífonos | — | SIM | SIM |
| AutoEQ interno (ParametricEQ/GraphicEQ → 10 bandas) | — | SIM (vectores) | SIM (vectores) |
| Gráfica de respuesta (predicción) | — | SIM | SIM |
| Modo demostración | — | SIM | SIM (emulador) |
| Ventana de ajustes nativa, abrir al iniciar sesión | — | SIM | — |
| Widgets (WidgetKit / Android) | — | PLAN | PLAN |
| Centro de Control / Ajustes rápidos | — | PLAN | PLAN |
| EQ DSP local (paramétrico) | — | PLAN (ADR-0008) | EXP/PLAN |

## Otros modelos

| Modelo | Detección por nombre | Catálogo de capacidades | Adaptador | Estado |
|---|---|---|---|---|
| WH-1000XM5 | Sí | Sí (5 bandas + Clear Bass) | No | PLAN — la app no envía ningún byte |
| WH-1000XM4 / XM3 / XM2 | Sí | Sí | No (MDR v1) | PLAN |
| MDR-1000X | Sí | Parcial | No | PLAN (sin capturas públicas) |

## Plataformas verificadas

| Plataforma | Verificación |
|---|---|
| macOS 26.5, Apple Silicon | Build + tests + app en modo demo |
| macOS, Intel (x86_64) | Binario universal; porción x86_64 ejecutada bajo Rosetta (`Code Type: X86-64 (translated)`). Sin prueba en un Mac Intel físico. |
| macOS 15 | Mínimo declarado; no probado en un equipo con macOS 15 |
| Android 15 (API 35, emulador arm64) | App en modo demo: conexión, lectura, EQ, perfiles, A/B |
| Android 8–14 | minSdk 26; no probado |
