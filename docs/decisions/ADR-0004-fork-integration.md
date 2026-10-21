# ADR-0004 — Integración de forks de XM6 Control

Fecha: 2026-10-21 · Estado: Aceptada

Análisis completo: [`docs/research/forks-analysis.md`](../research/forks-analysis.md).

| Componente | Origen | Decisión | Estado |
|---|---|---|---|
| Manifiesto Swift 6 / modo Swift 5 | mlazzarotto PR #2 | Ya en upstream (`b96d4fc`) | Presente |
| Canal bajo demanda, cola de comandos, cesión, ANC 0x19 | Anneo22 `1d3e3ec`, `efca6f6` (PR #4) | `git subtree merge` con autoría | **Integrado** |
| Init explícito de `ConnectionUsage` (tests compilan) | Anneo22 `b1ef964` | `git am` | **Integrado** |
| Recuperación de caché de módulos reubicada | plagnioj `72ddda5` | Idea reimplementada en `build_app.sh` | **Integrado** |
| Detección de ventanas visibles | Anneo22 `ControlSurfaceObserver` | Rediseñar con notificaciones documentadas de AppKit | Backlog P1 |
| Calidad de conexión (Quality/Stable) | Anneo22 `493360f`, `a6bbd2a`, `c3fd178` | Evaluar; Apache-2.0 | Backlog |
| Puente de comandos por carpeta temporal | Anneo22 `AgentControl` | No integrar (superficie de ataque local) | Rechazado |
| Widgets WidgetKit | yelloduxx `8b98102`, `0d71a47` | Portar con XcodeGen; IPC propia; comandos vía controlador | Backlog (Etapa 5) |
| Modo demo, abrir al iniciar sesión, timeout spinner multipunto | yelloduxx `78edeca`, `a962214`, `78d81b9` | Portar a mano | Backlog |
| Rediseño Liquid Glass completo | yelloduxx `78edeca` | No adoptar en bloque (exige macOS 26, identidad propia) | Rechazado |
| Fix botón ANC físico | yelloduxx `242cfe8` | Superado por `efca6f6` | No necesario |
| Controles del Centro de Control | plagnioj PR #1 | Solo referencia: su IPC no cruza procesos | Backlog (tras widgets) |

## Licencias
- Upstream (Rui Martins) y mlazzarotto: MIT.
- Anneo22 declara **Apache-2.0** para sus aportes (`NOTICE.md` de su fork). Compatible: se conserva
  `macos/LICENSE-APACHE` y el aviso en `THIRD_PARTY_NOTICES.md`. Pendiente: pedir confirmación al autor.
- plagnioj: solo se reutilizó la idea (no el texto) del script.
