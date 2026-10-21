# XM Audio Studio

Control y ecualización avanzada para los **Sony WH-1000XM6** desde **macOS** y **Android**, con
arquitectura preparada para el resto de la serie 1000X de diadema (MDR-1000X, XM2–XM5).

> Proyecto independiente, **no afiliado ni respaldado por Sony**. "Sony", "WH-1000XM6" y
> "Sound Connect" se usan solo para identificar el hardware compatible. Sony Sound Connect sigue
> siendo la herramienta oficial para firmware y funciones propietarias no implementadas aquí.

## Qué hay en este monorepo

| Ruta | Contenido |
|---|---|
| [`macos/`](macos/) | App nativa macOS (Swift/SwiftUI, SwiftPM). Basada en [XM6 Control](https://github.com/ruimartins23/xm6-control) (MIT) + la gestión de canal bajo demanda de Anneo22 (Apache-2.0). |
| [`android/`](android/) | App nativa Android (Kotlin, Jetpack Compose). `core/` es JVM puro (protocolo, sesión, EQ, presets); `app/` es transporte RFCOMM + UI. |
| [`shared/`](shared/) | Lo que mantiene a ambas apps en paridad: especificación del protocolo, fixtures de bytes, catálogo de modelos, esquema de perfiles, vectores de AutoEQ, tokens de diseño. |
| [`tools/`](tools/) | Generadores de referencia (Python, stdlib) de los fixtures compartidos. |
| [`docs/`](docs/) | Arquitectura, decisiones (ADR), Bluetooth, DSP, compatibilidad, pruebas, backlog. |

Las dos apps **no comparten código**: comparten especificación y pruebas. Cada fixture de
`shared/` se ejecuta en la suite de Swift y en la de Kotlin (ver [ADR-0002](docs/decisions/ADR-0002-shared-spec-not-shared-code.md)).

## Compilar y probar

Requisitos verificados: macOS 26, Xcode 26.6 / Swift 6.3, JDK 17, Android SDK 36.

```bash
# macOS: tests (protocolo, sesión, conformidad, EQ) y bundle .app
cd macos
swift test
Scripts/build_app.sh            # -> macos/.build/XM Audio Studio.app

# Android: tests JVM del core y APK de depuración
cd android
./gradlew :core:test
./gradlew :app:assembleDebug    # -> android/app/build/outputs/apk/debug/

# Regenerar fixtures compartidos (solo si cambia la especificación)
python3 tools/fixtures/generate_frames.py
python3 tools/fixtures/generate_eq_vectors.py
```

## Estado

Ver [`docs/compatibility/FEATURE-STATUS.md`](docs/compatibility/FEATURE-STATUS.md) para el estado
por función y plataforma, con su nivel de verificación (hardware / simulación / pendiente).
**Ninguna función se ha probado todavía con hardware real dentro de este proyecto**; el
procedimiento está en [`docs/testing/HARDWARE-TEST-PLAN.md`](docs/testing/HARDWARE-TEST-PLAN.md).

## Para continuar el desarrollo

Lee [`AGENTS.md`](AGENTS.md) (reglas y mapa del repo para personas y agentes), luego
[`docs/BACKLOG.md`](docs/BACKLOG.md) y el [`CHANGELOG.md`](CHANGELOG.md).

## Licencias

Código propio bajo MIT ([`LICENSE`](LICENSE)). Componentes de terceros y sus avisos en
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). No se incluyen datos de medición de AutoEq
(carecen de licencia): los perfiles medidos se importan por el usuario.
