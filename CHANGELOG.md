# Registro de cambios

## 0.2.0 — 2026-10-21 (en curso)
- macOS lee el estado real antes de escribir (intenciones codificadas tras la lectura, fusionadas por tipo)
  y nunca escribe al conectar (eliminado el forzado de "cancelación de ruido nivel 15" de upstream).
- Detección de ventanas visibles con notificaciones documentadas de AppKit (oclusión, Spaces, minimizar).
- Nivel de ambiente con estado "en curso"/"confirmado" como la app oficial (ambas plataformas).
- Android: mosaico de Ajustes rápidos y widget de control de ruido sobre la sesión compartida
  (lectura antes de escribir, liberación a 20 s, último estado conocido persistido).
- Adaptadores XM5 (v2) y MDR v1 (XM4/XM3/XM2) en Android con fixtures y salvaguarda contra `0x22`;
  Clear Bass en el modelo de EQ; códecs equivalentes en Swift.
- macOS: EQ avanzado (beta, apagado por defecto) — EQ paramétrico local vía process tap de Core Audio,
  margen automático, limitador, A/B y aviso de doble corrección (captura en vivo sin probar).
- macOS: widgets WidgetKit (pequeño, mediano, grande: batería, control de ruido, presets) con build XcodeGen
  (`Scripts/build_xcode.sh`) e IPC por App Group que encola los toques hasta leer el estado real.
- Perfiles v2 (Escenas): «Guardar configuración completa» guarda EQ + control de ruido, modo de escucha,
  sala BGM, Hablar para chatear, pausa y apagado automático; aplicar pide confirmación. Los archivos v1 se siguen importando.
- Prioridad de conexión Bluetooth (calidad de sonido / conexión estable) y códec actual, con tipo de
  conexión (clásico / LE Audio), en macOS y Android (WH-1000XM6). Baja latencia solo se muestra.
- Android: segmentos de igual altura; botones del ecualizador en filas en lugar de scroll horizontal.
- CI (GitHub Actions), `.dmg`, APK de versión firmado con clave local, guía de instalación, problemas conocidos.
- Prueba automática de escrituras con restauración: `tools/hardware/xm6_write_test.py`.

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
