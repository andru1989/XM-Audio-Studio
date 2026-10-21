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

> **Primera validación con hardware (2026-10-21, macOS 26.5, WH-1000XM6 del propietario):** la app de
> Mac conectó, negoció MDR v2 y **leyó** correctamente batería (49 %), control de ruido (ambiente nivel 20,
> luego desactivado), modo de escucha, lista multipunto (Mac + Galaxy S25 Ultra), pausa al quitarse,
> apagado automático y el EQ activo (un ajuste de usuario de Sound Connect, preset ≥ 0xA1).
> Las **escrituras** y toda la app Android siguen pendientes: [`HARDWARE-TEST-PLAN.md`](../testing/HARDWARE-TEST-PLAN.md).

## WH-1000XM6

| Función | Protocolo | macOS | Android |
|---|---|---|---|
| Conexión RFCOMM (UUID v2, SDP) | HW‑up | **HW** (lectura) | PEND (transporte propio) |
| Canal bajo demanda, liberación 20 s, cesión | — | SIM (32 tests de Anneo22) | SIM (14 tests de sesión) |
| Leer estado real antes de escribir tras reconectar | — | SIM (intenciones codificadas tras la lectura) | SIM |
| No escribir nada al conectar | — | SIM (eliminado el forzado de NC de upstream) | SIM |
| Escritura de nivel ambiente con estado "en curso"/"confirmado" | REF | SIM | SIM |
| Identificación (modelo `04 01`, firmware `04 02`) | REF | SIM | SIM |
| Batería y carga (tipo 00 y 08) | HW‑up (00) | **HW** (lectura) | SIM |
| ANC / ambiente / nivel (0x19 lectura, 0x17 escritura) | HW‑up | **HW** lectura · PEND escritura | SIM |
| Enfocar en la voz | PEND | SIM | SIM |
| Modo de escucha (Estándar/BGM/Cine) + sala | PEND | **HW** lectura · PEND escritura | SIM |
| Hablar para chatear + configuración | PEND | SIM | SIM |
| Pausa al quitarse | HW‑up | **HW** lectura · PEND escritura | SIM |
| Apagado automático (10/11) | HW‑up | **HW** lectura · PEND escritura | SIM |
| Lista multipunto | HW‑up | **HW** | SIM |
| Cambiar fuente de reproducción | PEND | SIM | SIM |
| EQ presets Sony (00, 30–33) | HW‑up | SIM | SIM |
| EQ 10 bandas −6…+6 | HW‑up | SIM | SIM |
| Capacidad EQ `50 04 01` (bandas/pasos) | REF | SIM (solo lectura) | SIM (solo lectura) |
| Perfiles ilimitados, duplicar, renombrar, borrar | — | SIM (tests) | SIM (tests) |
| Exportar / importar perfiles (formato compartido) | — | SIM | SIM |
| Deshacer, A/B | — | SIM | SIM (emulador) |
| Guardar en la app la curva/ajuste de usuario que ya tienen los audífonos | — | **HW** (detección del ajuste de Sound Connect) | SIM |
| AutoEQ interno (ParametricEQ/GraphicEQ → 10 bandas) | — | SIM (vectores) | SIM (vectores) |
| Gráfica de respuesta (predicción) | — | SIM | SIM |
| Modo demostración | — | SIM | SIM (emulador) |
| Foto del modelo detectado (fotos de Sony, uso personal) | — | **HW** | SIM |
| Ventana de ajustes nativa, abrir al iniciar sesión | — | SIM | — |
| Widgets (WidgetKit / Android) | — | en curso | SIM (emulador: modelo, batería, modo, 3 botones) |
| Centro de Control / Ajustes rápidos | — | PLAN | SIM (mosaico NC → Ambiente → Off) |
| EQ DSP local (paramétrico) | — | EXP (cadena DSP con 26 tests; captura en vivo sin probar) | PLAN |

## Otros modelos

Detalle por función y evidencia: [`OTHER-MODELS.md`](OTHER-MODELS.md). Ninguno probado en hardware.

| Modelo | Adaptador | Estado (Android) | macOS |
|---|---|---|---|
| WH-1000XM5 | `Xm5Adapter` (v2) | PEND batería, NC 0x17, EQ curva (Clear Bass + 5), STC · EXP presets · PLAN apagado/pausa | solo códec + tests |
| WH-1000XM4 | `MdrV1Adapter` | PEND batería, NC, EQ · EXP STC · PLAN apagado/pausa | solo códec + tests |
| WH-1000XM3 | `MdrV1Adapter` | PEND batería, NC · EXP EQ (frecuencias verificadas por captura) · PLAN apagado | solo códec + tests |
| WH-1000XM2 | `MdrV1Adapter` | EXP todo (sin capturas públicas) | solo códec + tests |
| MDR-1000X | ninguno | PLAN — no se envía ningún byte | — |

## Plataformas verificadas

| Plataforma | Verificación |
|---|---|
| macOS 26.5, Apple Silicon | Build + tests + app en modo demo |
| macOS, Intel (x86_64) | Binario universal; porción x86_64 ejecutada bajo Rosetta (`Code Type: X86-64 (translated)`). Sin prueba en un Mac Intel físico. |
| macOS 15 | Mínimo declarado; no probado en un equipo con macOS 15 |
| Android 15 (API 35, emulador arm64) | App en modo demo: conexión, lectura, EQ, perfiles, A/B |
| Android 8–14 | minSdk 26; no probado |
