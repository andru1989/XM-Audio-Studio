# Avisos de terceros

XM Audio Studio incorpora o se basa en el trabajo siguiente. Se conservan los avisos requeridos.

## Código incorporado

### XM6 Control — base de `macos/`
- Fuente: https://github.com/ruimartins23/xm6-control (importado en `af6d89b`, con historial)
- Licencia: MIT, Copyright (c) 2026 Rui Martins — texto completo en [`macos/LICENSE`](macos/LICENSE).
- Aplica a todo el material original de upstream, incluidas las partes de upstream de archivos modificados.
- El port a Kotlin de `android/core/protocol` deriva de este código (MIT).

### Anneo22 — canal de control bajo demanda y estado ANC de firmware 3.x
- Fuente: https://github.com/Anneo22/xm6-control, commits `1d3e3ec`, `efca6f6`, `b1ef964`
  (enviados como PR #4 a ruimartins23/xm6-control).
- Licencia declarada por el autor para sus aportes: **Apache License 2.0**, Copyright 2026 Anneo22 —
  texto en [`macos/LICENSE-APACHE`](macos/LICENSE-APACHE). Archivos afectados: `ConnectionUsage.swift`,
  partes de `HeadphonesController.swift`, `RFCOMMConnection.swift`, `Commands.swift`,
  `AmbientSoundState.swift`, `ControlSurfaceObserver.swift`, `AppSettings.swift`, y los tests
  `HeadphonesControllerTests.swift`, `ConnectionUsageTests.swift`, `AmbientSoundTests.swift`.
- Cambios propios posteriores: renombrado, consultas de identificación, ajustes de tests (ver historial git).
- La reimplementación Kotlin del mismo diseño (`android/core/session`) es código propio, no una copia.

### mlazzarotto — manifiesto SwiftPM
- PR #2 a ruimartins23/xm6-control (MIT), ya integrado en upstream.

### plagnioj — recuperación de caché de módulos en `build_app.sh`
- Idea tomada de `72ddda5` (https://github.com/plagnioj/xm6-control), reimplementada.

## Referencias técnicas (no se copió código)

| Proyecto | Licencia | Uso |
|---|---|---|
| [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) | AGPL-3.0-or-later | Solo hechos de protocolo (interoperabilidad). XM6 Control declara que sus layouts se leyeron de Gadgetbridge. |
| [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient) | MIT | Tablas de protocolo v1/v2, identificación, NCASM 0x17/0x19, EQ. Reutilizable con aviso. |
| [Plutoberth/SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient) | MIT | Referencia histórica. |
| [marconvcm/sony-device-center](https://github.com/marconvcm/sony-device-center) | MIT | Capturas XM6 del EQ (fw 3.1.5). |
| [shellingtonshreyas/xm6-macos-controller](https://github.com/shellingtonshreyas/xm6-macos-controller) | GPL-3.0 | Solo referencia. |
| [ibatra/sony-headphones-client](https://github.com/ibatra/sony-headphones-client) | MIT declarado sin texto | Solo referencia. |
| [ParaEQ](https://github.com/wabsto1/ParaEQ) | MIT | Diseño del futuro motor DSP macOS (ADR-0008). Aún no incorporado. |
| [AutoEq](https://github.com/jaakkopasanen/AutoEq) | MIT (software) | Formatos `ParametricEQ.txt`/`GraphicEQ.txt`. **Los datos de medición no tienen licencia y no se incluyen.** |

Nota: las tablas de mos9527 provienen de ingeniería inversa de la app oficial de Sony con fines de
interoperabilidad. Este proyecto no distribuye binarios, imágenes, logotipos ni recursos de Sony.

## Dependencias de compilación (Android)
AndroidX (Apache-2.0), Jetpack Compose (Apache-2.0), Kotlin stdlib/coroutines/serialization (Apache-2.0).
macOS no tiene dependencias externas (solo frameworks del sistema).
