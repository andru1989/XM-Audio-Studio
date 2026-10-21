# ADR-0003 — Canal de control bajo demanda

Fecha: 2026-10-21 · Estado: Aceptada

## Contexto
Los XM6 aceptan **una sola sesión de control MDR** a la vez, aunque el audio esté conectado por
multipunto a varios dispositivos. Si el Mac retiene el canal, Sony Sound Connect (o nuestra app
Android) no puede controlar los audífonos.

## Decisión
Misma política en ambas plataformas (macOS: diseño de Anneo22, PR #4; Android: reimplementación
propia en `core/session/HeadphoneSession.kt`):

1. El canal se adquiere cuando una superficie de control se hace visible o el usuario emite un comando.
2. Se libera **20 s** después de la última actividad si no hay superficies visibles. El tráfico de
   fondo (lecturas de estado, notificaciones) **no** prolonga la gracia.
3. No se libera con un comando aceptado pendiente de ACK.
4. Si los audífonos cierran un canal **inactivo**, otro dispositivo tomó el control: estado
   "cedido" (YIELDED), **sin reconexión automática** hasta nueva actividad del usuario.
5. Fallos al abrir (canal ocupado/apagado) → error explicado, sin bucles de reintento. Un timeout de
   conexión (8 s) admite un reintento silencioso.
6. "Desconectar" del usuario libera de inmediato.

### Mejora sobre el PR #4 (Android ya, macOS en backlog)
Tras cada (re)conexión, la sesión **lee el estado real antes de escribir**. Los comandos emitidos
mientras no hay canal se guardan como **intenciones** (`DeviceCommand`) y se codifican después de la
lectura, contra el estado fresco. Así nunca se escribe un valor obsoleto (p. ej. cambiar a modo BGM
conserva el tamaño de sala real que eligió el usuario en el teléfono). En el PR #4 los comandos
pendientes se envían antes que las lecturas.

Las intenciones del mismo tipo se **fusionan** (la última gana) mientras no se envían, lo que limita
la tasa de escrituras al arrastrar deslizadores del ecualizador (stop-and-wait + coalescencia).

## Consecuencias
- Coexistencia real Mac ↔ Android ↔ Sound Connect.
- La UI debe distinguir "estado en vivo" de "último estado conocido" (`stateIsLive`).
- Tests: `HeadphoneSessionTest.kt` (14 casos con XM6 simulado) y `HeadphonesControllerTests.swift`
  (32 casos de Anneo22).
