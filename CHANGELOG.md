# Registro de cambios

## 0.1.0 — 2026-10-21 (primera entrega, sin validación en hardware)

### Monorepo y base
- Monorepo `xm-audio-studio`: `macos/`, `android/`, `shared/`, `tools/`, `docs/`.
- `macos/` importado de ruimartins23/xm6-control@af6d89b con historial (git subtree).
- Integrado PR #4 de Anneo22 (canal bajo demanda, cesión, ANC 0x19) + `b1ef964`; idea de `72ddda5` (plagnioj).
- Renombrado a XM Audio Studio; eliminada la foto de producto de Sony.

### Protocolo y paridad
- Especificación `shared/protocol-spec/PROTOCOL.md` y catálogo `models.json` (MDR-1000X…XM6).
- Fixtures compartidos: 11 frames, 27 eventos, 22 comandos (incluye capturas reales documentadas).
- Identificación (`04 01`, `04 02`), batería tipo 08, capacidad del EQ (`50 04 01`).

### Android (nuevo)
- `core` JVM: protocolo, sesión bajo demanda que lee el estado antes de escribir, XM6 simulado, EQ, perfiles.
- `app`: transporte RFCOMM, UI Compose (inicio, ecualizador, ajustes), modo demo, español/inglés.

### macOS
- Estudio de ecualización, perfiles, AutoEQ, A/B, deshacer, exportar/importar, modo demo.
- Ajustes nativos, abrir al iniciar sesión, localización al español, diseño plano.
- Binario universal arm64 + x86_64; mínimo macOS 15.

### Imágenes
- Fotos de producto de los seis modelos (© Sony, sony.com) para uso personal, por decisión del propietario; ver `shared/model-images/SOURCES.md`. Sustituyen a las fotos CC de XM2/XM3.

### Primera prueba con hardware
- macOS leyó correctamente el estado completo de un WH-1000XM6 real (ver FEATURE-STATUS).
- Importar a la biblioteca un ajuste de usuario de Sound Connect (preset ≥ 0xA1) usando las bandas que reporta (Mac y Android); corregido un fallo en Android al guardar en ese caso.

### Pruebas
- Kotlin: 34 tests. Swift: 59 tests. Emulador Android y app macOS verificados en modo demo.
