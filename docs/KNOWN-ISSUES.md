# Problemas conocidos y riesgos

Actualizado: 2026-10-21.

## Validación
- **Escrituras sin probar en hardware**: la lectura completa del XM6 está verificada en macOS; las
  escrituras se probarán con `tools/hardware/xm6_write_test.py` (los audífonos estaban apagados).
- **Android sin probar en hardware** (solo emulador en modo demo).
- Frecuencias de banda del EQ (31…16 kHz) y 1 dB por paso: etiquetas/supuestos, no confirmados por el dispositivo.
- Significado de los bytes [7]/[8] del informe NC `0x19` (auto‑ambiente, sensibilidad) sin confirmar;
  las escrituras usan el formato `0x17`, que no los incluye (verificado por upstream en fw 3.0.0).

## Funcionamiento
- Coexistencia: los audífonos aceptan una sola sesión de control. Con Sound Connect abierto en el
  teléfono, el Mac/Android mostrarán error al conectar (por diseño, sin reintentos agresivos).
- macOS: si los audífonos tienen activo un ajuste de usuario de Sound Connect, solo se puede importar
  su curva (no su nombre ni otros ajustes guardados en la app oficial).
- macOS: los comandos codificados justo antes de un corte del canal pueden perderse (Android los
  re‑encola); el usuario verá el estado real al reconectar.
- AutoEQ: la corrección se ajusta a 10 bandas fijas de ±6 pasos; correcciones grandes o muy
  estrechas no se reproducen (se muestra el error RMS).

## Distribución y licencias
- `.app`/`.dmg` sin notarizar (requiere Developer ID).
- Fotos de producto © Sony incluidas para uso personal: **retirar antes de publicar** el repositorio o las apps.
- Aportes de Anneo22 declarados Apache‑2.0: compatible, pendiente de confirmación del autor.
- Datos de medición de AutoEq: sin licencia; no se incluyen (el usuario importa sus archivos).

## Técnicos
- AGP 8.13 (pendiente migración a AGP 9).
- Mínimo macOS 15 por `restorationBehavior`; macOS 14 no soportado.
- R8 en el APK de versión: no se ha hecho prueba manual del APK minificado en un dispositivo.
