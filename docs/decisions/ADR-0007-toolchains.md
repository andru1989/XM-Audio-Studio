# ADR-0007 — Toolchains y mínimos de plataforma

Fecha: 2026-10-21 · Estado: Aceptada

| Plataforma | Elección | Motivo |
|---|---|---|
| macOS mínimo | **15.0** (antes 14.0) | `restorationBehavior(.disabled)` en escenas (15) para que las ventanas abran a su tamaño por defecto; WidgetKit interactivo (14), `SMAppService` (13), process taps del DSP (14.4). Liquid Glass solo con `#available(macOS 26)`. Cambio del 2026-10-21 al validar que el contenido cabe sin scroll. |
| Swift | tools 6.0, modo de lenguaje 5 | Heredado de upstream (PR #2); compila con Swift 6.3. |
| Android | compileSdk/targetSdk 36, **minSdk 26** | Permisos Bluetooth: `BLUETOOTH_CONNECT` en 31+, legados con `maxSdkVersion=30`. |
| AGP / Gradle | 8.13.2 / 8.14.3 | Última línea AGP 8 estable; AGP 9 cambia el DSL de Kotlin integrado → migración en backlog. |
| Kotlin / Compose BOM | 2.2.21 / 2025.12.01 | Compatibles entre sí; sin depender de metadatos de Kotlin 2.3+. |
| Dependencias Android | coroutines, kotlinx-serialization, AndroidX Compose/Lifecycle | Sin DI, sin red, sin analítica. |
