# ADR-0001 — Monorepo con dos apps nativas; macOS a partir de XM6 Control

Fecha: 2026-10-21 · Estado: Aceptada

## Contexto
El encargo exige dos apps nativas (macOS y Android) desde la primera entrega, reutilizando
XM6 Control (Swift, MIT) como base de macOS, sin servidores ni cuentas.

## Decisión
- Un único repositorio `xm-audio-studio` con `macos/`, `android/`, `shared/`, `docs/`, `tools/`.
- `macos/` se importó con `git subtree add` desde `ruimartins23/xm6-control@af6d89b` **con historial
  completo**, para conservar autoría y poder traer cambios futuros con `git subtree merge`.
- macOS sigue siendo un paquete SwiftPM (compila sin Xcode con `swift build`); el `.app` se
  ensambla con `Scripts/build_app.sh`. Los widgets (WidgetKit) necesitarán un proyecto Xcode generado
  con XcodeGen en una etapa posterior (ver ADR-0004).
- Android: proyecto Gradle con módulo `core` JVM puro (testeable sin emulador) y `app`.

## Consecuencias
- Builds y tests independientes por plataforma; la paridad se garantiza con `shared/` (ADR-0002).
- Renombrar `Sources/XM6Control` → `Sources/XMAudioStudio` hará que futuros `subtree merge` de upstream
  requieran resolver renombrados a mano.
