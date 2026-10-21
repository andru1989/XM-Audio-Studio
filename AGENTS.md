# Guía para agentes y colaboradores

Este archivo es el punto de entrada para cualquier persona o agente (Claude Code, Cowork, otros)
que continúe el desarrollo. `CLAUDE.md` apunta aquí.

## Contexto en 30 segundos

- Producto: **XM Audio Studio**, control + ecualización para Sony WH-1000XM6 en macOS y Android.
- El encargo original completo está en [`docs/PROMPT.md`](docs/PROMPT.md). Léelo si vas a tomar
  decisiones de alcance.
- Idioma del producto: español primero, inglés preparado. Código, identificadores y commits en inglés;
  documentación de proyecto en español.
- Estado y siguiente trabajo: [`docs/BACKLOG.md`](docs/BACKLOG.md), [`CHANGELOG.md`](CHANGELOG.md),
  [`docs/compatibility/FEATURE-STATUS.md`](docs/compatibility/FEATURE-STATUS.md).

## Mapa del repositorio

```
macos/                      SwiftPM. Base: ruimartins23/xm6-control (historial importado con git subtree)
  Sources/SonyHeadphonesKit Protocolo MDR, transporte RFCOMM (IOBluetooth), HeadphonesController
  Sources/AudioStudioKit    Biquads, AutoEq, ajuste a bandas fijas, perfiles (sin Bluetooth ni UI)
  Sources/XMAudioStudio     App SwiftUI (barra de menús, ventana, panel)
  Sources/XMAudioProbe      CLI de desarrollo para enviar payloads crudos al hardware
  Tests/                    XCTest; incluye las pruebas de conformidad con shared/
android/                    Gradle (AGP 8.13, Kotlin 2.2, Compose)
  core/                     JVM puro: protocol/, device/, session/, eq/, presets/
  app/                      Transporte RFCOMM, persistencia, UI Compose
shared/
  protocol-spec/            PROTOCOL.md + models.json (catálogo de capacidades por modelo)
  protocol-fixtures/        frames.json, events.json, commands.json
  preset-schema/            Esquema JSON de perfiles + perfiles incluidos
  autoeq-test-vectors/      vectors.json (biquads y ajustes de referencia)
  design-tokens/            tokens.json (colores, radios, espaciado)
tools/fixtures/             Generadores Python (stdlib) de los fixtures
docs/                       architecture/, bluetooth/, dsp/, decisions/ (ADR), compatibility/, testing/
```

## Comandos

```bash
cd macos && swift test                       # ~40 s (tests de sesión usan tiempo real)
cd macos && Scripts/build_app.sh             # .app firmado (Apple Development si existe)
cd android && ./gradlew :core:test           # JVM, rápido
cd android && ./gradlew :app:assembleDebug   # APK
python3 tools/fixtures/generate_frames.py    # solo si cambia el formato de frame
python3 tools/fixtures/generate_eq_vectors.py
```

## Reglas no negociables (del encargo)

1. **No inventar comandos Bluetooth.** Todo byte de escritura debe venir de una fuente documentada
   (captura real o referencia MIT) y quedar en `shared/protocol-spec/PROTOCOL.md` y en los fixtures.
2. **No enviar nada a modelos sin adaptador.** `ProtocolAdapter.forModel` (Kotlin) decide qué modelos
   reciben bytes: XM6 (`Xm6Adapter`), XM5 (`Xm5Adapter`, v2) y XM4/XM3/XM2 (`MdrV1Adapter`); MDR-1000X no.
   macOS solo controla el XM6 (los códecs v1/XM5 existen en Swift pero no están conectados al controlador).
   En MDR v1 el opcode `0x22` es *apagar*; en v2 es *batería*: el adaptador v1 bloquea cualquier payload que
   empiece por `0x22`. Si el init responde con otra familia que la esperada, la sesión aborta sin escribir.
3. **Leer el estado real antes de escribir** tras cada (re)conexión; nunca restaurar presets obsoletos.
4. **No declarar soporte de hardware sin prueba real.** Usa los niveles de
   `docs/compatibility/FEATURE-STATUS.md` (hardware / simulación / pendiente / experimental).
5. **Paridad Mac/Android por fixtures**: cualquier cambio de codificación o decodificación se refleja
   en `shared/protocol-fixtures/*.json` y debe pasar en ambas suites.
6. **Licencias**: Gadgetbridge (AGPL) y xm6-macos-controller (GPL) solo como referencia, nunca copiar
   código. mos9527 / Plutoberth / sony-device-center (MIT) se pueden reutilizar con aviso. Datos de
   AutoEq: no incluir en el repo ni en las apps (sin licencia).
7. Sin firmware, sin restablecimiento de fábrica, sin borrar emparejamientos, sin telemetría.
7b. Las fotos de `shared/model-images/` son © Sony, sin licencia de redistribución. El propietario decidió
    publicarlas en el repo público (2026-10-21) asumiendo el riesgo; no las quites sin que él lo pida, y
    retíralas si llega una solicitud del titular.
8. Commits pequeños; ejecutar las dos suites tras cambios que toquen protocolo o EQ.

## Dónde está cada decisión

`docs/decisions/` contiene los ADR numerados. Si cambias una decisión, añade un ADR nuevo que
reemplace al anterior en lugar de reescribirlo.

## Remotos git

`xm6-upstream` (ruimartins23/xm6-control) y `anneo22` están configurados para seguir upstream.
Para traer cambios futuros: `git fetch xm6-upstream && git subtree merge --prefix=macos xm6-upstream/main`
(revisar conflictos con el renombrado de `Sources/XM6Control` → `Sources/XMAudioStudio`).
