# Backlog priorizado (por dependencias)

Actualizado: 2026-10-21. Estado detallado en `compatibility/FEATURE-STATUS.md`.

## P0 — Validación con hardware (bloquea todo lo demás)
1. Ejecutar `testing/HARDWARE-TEST-PLAN.md` con WH-1000XM6 reales en Mac y Android; subir niveles a HW.
2. Confirmar con lecturas: `50 04 01` (bandas/pasos), tipo de batería, bytes [7]/[8] de `67 19`.
3. Corregir lo que falle en el transporte Android (`RfcommControlChannel`) con logs reales.

## P1 — Paridad y robustez
4. macOS: "leer estado real antes de escribir" al readquirir (hoy el PR #4 envía comandos pendientes primero).
   Opción: portar `HeadphoneSession` a Swift o adaptar `HeadphonesController` (ADR-0003).
5. macOS: rediseñar `ControlSurfaceObserver` con notificaciones documentadas de AppKit
   (oclusión, miniaturizar, cerrar, ocultar app) + tests; ventana en otro Space no debe retener el canal.
6. Escritura de NC durante arrastre con `valueChangeStatus = 00` y `01` al soltar (como la app oficial).
7. Escenas: guardar/aplicar la configuración completa (ANC, nivel, modo de escucha, STC, EQ) como perfil,
   extendiendo `shared/preset-schema` (v2).
8. Tests de UI: Compose UI tests (Android) y snapshot/XCUITest básicos (macOS).
9. CI (GitHub Actions): `swift test`, `./gradlew :core:test :app:assembleDebug`, fixtures regenerados sin diff.

## P2 — Funciones de plataforma
10. macOS widgets WidgetKit (XcodeGen; IPC por App Group con el Team ID; comandos vía controlador) — idea de yelloduxx.
11. macOS controles del Centro de Control (tras widgets) — idea de plagnioj con IPC correcta.
12. Android: Ajustes rápidos (TileService) y widget (Glance).
13. Imágenes por modelo: hechas para XM2 y XM3 (CC BY, `shared/model-images`). Sin foto libre para MDR-1000X, XM4, XM5, XM6: buscar de nuevo más adelante, pedir permiso a autores o hacer fotos propias. Android: permitir foto propia del usuario.
14. Calidad de conexión (Quality/Stable) — commits de Anneo22 `493360f`, `a6bbd2a`, `c3fd178` (Apache-2.0).

## P3 — DSP y AutoEQ avanzado
15. Motor DSP macOS (ADR-0008, ParaEQ MIT): process taps, biquads vDSP, limitador, bypass, A/B, analizador.
16. Evitar doble corrección: si el EQ interno lleva un perfil AutoEQ, bloquear el mismo en DSP.
17. Descarga opcional de perfiles AutoEq a petición del usuario, con atribución de la fuente de medición.
18. Android DSP experimental (DynamicsProcessing), solo tras validar en dispositivos reales.

## P4 — Otros modelos (etapa final del encargo)
19. Adaptador XM5 (MDR v2, NC 0x17, EQ tipo 00 de 6 bytes ±10) + fixtures de captura real.
20. Adaptador MDR v1 (XM4 → XM3 → XM2) con tablas y polaridad propias (v1 `0x22` = apagar).
21. MDR-1000X: requiere capturas.

## Deuda técnica
- AGP 9 / Kotlin 2.3+ (migración de DSL).
- `ProtocolLog` (macOS) escribe en el hilo principal; mover a cola propia.
- Notarización y `.dmg` firmados para distribución (requiere Developer ID).
- Confirmar con Anneo22 la licencia de sus aportes (declarados Apache-2.0).
