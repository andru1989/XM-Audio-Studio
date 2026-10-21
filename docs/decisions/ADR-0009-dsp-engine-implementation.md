# ADR-0009 — Motor DSP de macOS: implementación (precisa ADR-0008)

Fecha: 2026-10-21 · Estado: Aceptada (beta; captura en vivo sin verificar)

## Contexto
ADR-0008 propuso un motor DSP local basado en ParaEQ (MIT): process taps de Core Audio, agregado
privado con la salida real como subdispositivo principal, `vDSP_biquadm`, preamplificación automática y
limitador con anticipación, sin driver virtual y sin cambiar la salida por defecto. Se implementó en el
target `AudioDSPKit` (`macos/Sources/AudioDSPKit`) con la UI "EQ avanzado" (pestaña ámbar del estudio).
Detalle técnico: [`docs/dsp/DSP-ENGINE.md`](../dsp/DSP-ENGINE.md).

## Decisión (cambios respecto de ADR-0008)
1. **Tap por dispositivo, no global.** Se usa `CATapDescription(excludingProcesses: [nuestro proceso],
   deviceUID: salidaElegida, stream: 0)`: solo se procesa el audio que las apps envían a la salida
   elegida. El tap global estéreo de ParaEQ capturaría y redirigiría también el audio destinado a otros
   dispositivos (p. ej. un DAW en una interfaz), lo que contradice "solo procesar la salida elegida".
   El diseño de ParaEQ queda como modo de diagnóstico (`dsp.tapScope = systemWide`), que además solo
   funciona mientras la salida elegida es la salida del sistema.
2. **Biquads en doble precisión** (`vDSP_biquadmD`). En `Float` los filtros graves con Q alta dejaban un
   error de ~1e‑4 (−70 dB) incluso con ganancia 0 dB. Coste despreciable (≤ 20 secciones).
3. **Apagado en cada arranque.** Se recuerdan bandas, preamplificación y salida; nunca el estado ON.
4. **Fallo seguro ante disposiciones inesperadas** del agregado (canales del tap, flujos de entrada del
   propio dispositivo): se desmonta todo y el audio sigue sin procesar.
5. **`AudioDSPKit` siempre optimizado** (`-O` también en debug): Swift sin optimizar asigna memoria en
   bucles `for` simples, inaceptable en el hilo de E/S. `RealtimeAllocationTests` lo comprueba.
6. **Doble corrección**: aplicar una curva AutoEQ en DSP, o activar el DSP con una, mientras los
   audífonos tienen un perfil `measured` activo exige confirmación explícita (o apagar antes el EQ de
   los audífonos); un aviso permanece mientras ambos se apliquen.
7. Permiso: `NSAudioCaptureUsageDescription` + entitlement `com.apple.security.device.audio-input`
   (runtime endurecido, app sin sandbox). `build_app.sh` firma con `XMAudioStudio.entitlements`.

## Consecuencias
- Si el usuario elige una salida que no es la del sistema, solo se procesan las apps que reproducen
  directamente en ella; la UI lo explica. Nunca se cambia la salida del sistema.
- La variante por dispositivo de `CATapDescription` no está probada en hardware por ParaEQ (que solo
  usa el tap global) ni por este proyecto: si en la prueba manual resulta silenciosa, el modo
  `systemWide` es la alternativa documentada.
- Las funciones de control del XM6 no dependen del motor DSP (target separado, sin Bluetooth).
