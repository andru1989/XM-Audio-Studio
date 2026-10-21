# Backlog priorizado (por dependencias)

Actualizado: 2026-10-21. Estado detallado en `compatibility/FEATURE-STATUS.md`.

## P0 — Validación con hardware (bloquea todo lo demás)
1. Ejecutar `testing/HARDWARE-TEST-PLAN.md` con WH-1000XM6 reales en Mac y Android; subir niveles a HW.
2. Confirmar con lecturas: `50 04 01` (bandas/pasos), tipo de batería, bytes [7]/[8] de `67 19`.
3. Corregir lo que falle en el transporte Android (`RfcommControlChannel`) con logs reales.

## P1 — Paridad y robustez
4. ~~macOS: leer estado real antes de escribir~~ — hecho (0.2.0).
5. ~~`ControlSurfaceObserver` con notificaciones documentadas~~ — hecho; falta test automatizado de AppKit.
6. ~~valueChangeStatus en arrastre~~ — hecho.
7. ~~Escenas~~ — hechas (formato v2). Pendiente: sensibilidad/tiempo de STC, fuente multipunto y DSP local en la escena; deshacer escena completa.
8. Tests de UI: Compose UI tests (Android) y snapshot/XCUITest básicos (macOS).
9. ~~CI~~ — workflow creado; se ejecutará cuando el repo esté en GitHub.

## P2 — Funciones de plataforma
10. ~~macOS widgets WidgetKit~~ — hechos; falta comprobar en pantalla la galería y los clics (parámetros de botones).
11. macOS controles del Centro de Control (tras widgets) — idea de plagnioj con IPC correcta.
12. ~~Android: Ajustes rápidos y widget~~ — hecho (simulación).
13. Imágenes por modelo: hechas para XM2 y XM3 (CC BY, `shared/model-images`). Sin foto libre para MDR-1000X, XM4, XM5, XM6: buscar de nuevo más adelante, pedir permiso a autores o hacer fotos propias. Android: permitir foto propia del usuario.
14. Calidad de conexión (Quality/Stable) — commits de Anneo22 `493360f`, `a6bbd2a`, `c3fd178` (Apache-2.0).

## P3 — DSP y AutoEQ avanzado
15. Motor DSP macOS — hecho en beta (ADR-0009); falta prueba en vivo y analizador de espectro.
16. ~~Evitar doble corrección~~ — hecho (confirmación + aviso).
17. Descarga opcional de perfiles AutoEq a petición del usuario, con atribución de la fuente de medición.
18. Android DSP experimental (DynamicsProcessing), solo tras validar en dispositivos reales.

## P4 — Otros modelos (etapa final del encargo)
19. ~~Adaptadores XM5 y MDR v1~~ — hechos en Android (sin hardware); conectar los códecs al controlador de macOS.
20. Responder la alerta de cambio de modo del XM3 al cambiar preset; apagado automático v1 temporizado.
21. MDR-1000X: requiere capturas.

## Deuda técnica
- AGP 9 / Kotlin 2.3+ (migración de DSL).
- Notarización (requiere Developer ID); `.dmg` ya se genera firmado.
- Confirmar con Anneo22 la licencia de sus aportes (declarados Apache-2.0).
