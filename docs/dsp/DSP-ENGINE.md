# Motor DSP local de macOS ("EQ avanzado")

Estado: **beta, opcional y apagado por defecto**. El procesamiento DSP está cubierto por tests
unitarios. La **captura en vivo del audio del sistema no se ha verificado** (ver
[Qué se verificó](#qué-se-verificó)). Decisiones: [ADR-0008](../decisions/ADR-0008-dsp-engine-macos.md)
(propuesta) y [ADR-0009](../decisions/ADR-0009-dsp-engine-implementation.md) (implementación).

El EQ avanzado (ámbar) es un ecualizador paramétrico que corre **en este Mac** sobre el audio que se
envía a una salida elegida. Es independiente del EQ de los audífonos (azul): no usa Bluetooth y no
cambia nada en los audífonos. La única excepción es el botón explícito "Apagar el EQ de los audífonos"
del aviso de doble corrección, que usa el mismo camino que elegir "Original Sony" en la biblioteca.

## Arquitectura

```
Apps → (audio destinado a la salida elegida)
  → process tap de Core Audio   CATapDescription(excludingProcesses: [este proceso],
                                 deviceUID: salida, stream: 0), privado, .mutedWhenTapped
  → agregado privado            subdispositivo principal = la salida real; tap en TapList
                                 (compensación de deriva); IsPrivate, no apilado
  → un único IOProc (cola serie propia):
      TapStaging.stage     entrada del tap (Float32 entrelazado o planar) → buffers planares
      DSPProcessor         preamp (rampa por bloque) → biquads vDSP_biquadmD (doble precisión,
                           20 secciones por canal, unidad las no usadas) → limitador enlazado
                           con anticipación de 5 ms + recorte duro a ±1 (NaN → 0)
                           → mezcla A/B (fundido de 10 ms; al terminar, la salida es la entrada bit a bit)
      TapStaging.write     buffers planares → salida del dispositivo (canal j ← canal j mod N;
                           estéreo sobre salida mono = mezcla)
```

| Archivo (`macos/Sources/AudioDSPKit`) | Papel |
|---|---|
| `DSPSettings.swift` | Bandas (tipo, frecuencia, ganancia, Q, canales L/R/ambos, activa), preamp manual, margen automático, bypass, origen AutoEQ. `sanitized()` acota todo. |
| `DSPCoefficientPlan.swift` | Matriz de coeficientes por canal para una frecuencia de muestreo, usando los biquads RBJ de `AudioStudioKit`. Frecuencia acotada a 10 Hz…0,499·fs; banda de 0 dB = identidad exacta. Calcula el pico de la curva y el preamp efectivo (margen automático = −máx(0, pico)). Es la misma fuente para la gráfica. |
| `BiquadBank.swift` | Envoltorio de `vDSP_biquadmD` (adaptado de ParaEQ, MIT). Rampas `SetTargetsDoubleD` con el orden de argumentos correcto (gotcha 13 de ParaEQ). |
| `PeakLimiter.swift` | Limitador con anticipación, N canales enlazados (adaptado de ParaEQ, MIT). Buffers dimensionados para 768 kHz: cambiar de fs no reserva memoria. |
| `DSPProcessor.swift` | La cadena. API offline `process(buffer:)` (tests) y de tiempo real `process(channels:frameCount:)`; las dos ejecutan el mismo código. |
| `TapIOContext.swift` | Cuerpo del IOProc y conversión de buffers de Core Audio; contadores atómicos (llamadas, pico de entrada). |
| `ProcessTapEngine.swift` | Ciclo de vida: tap, agregado, IOProc, listeners, watchdog, desmontaje (diseño de ParaEQ, MIT). |
| `AudioOutputDevices.swift`, `AudioDeviceWatcher.swift` | Enumeración de salidas (solo lectura; oculta nuestro agregado) y avisos de cambios para la UI. |

App: `Studio/DSPStudioModel.swift` (estado, persistencia, doble corrección) y
`Studio/DSPEqualizerView.swift` (pestaña ámbar del estudio).

### Hilos y tiempo real
- **Control** (hilo principal): `apply(settings)`, `setSampleRate`, la UI. Calcula coeficientes en
  `Double` y los copia a un bloque compartido protegido por `os_unfair_lock`.
- **Render** (cola de E/S): al inicio de cada bloque hace `os_unfair_lock_trylock`. Si lo consigue y hay
  cambios, llama a `SetTargetsDoubleD` (rampa sin clics) o, si cambió fs, a `SetCoefficientsDoubleD` y
  reinicia el estado de filtros y limitador. Si el candado está ocupado, sigue con los parámetros
  anteriores durante un bloque. Ninguna llamada a vDSP sobre el setup ocurre fuera del hilo de render,
  porque los setups de vDSP no son seguros entre hilos.
- Sin asignaciones de memoria, sin bloqueos y sin Objective-C en el render. `AudioDSPKit` se compila con
  `-O` también en debug, porque Swift sin optimizar asigna memoria incluso en `for i in 0..<n`.
- La entrada no finita o absurda (|x| > 1000 tras el preamp) se convierte en silencio antes de los
  IIR. Así una muestra NaN no envenena el estado de los filtros para siempre.

### Ciclo de vida y cambios de dispositivo
- **Encender**: se crea el tap; se lee su formato (debe ser Float32, de 1 a 8 canales); se crea el
  procesador a esa fs; se crea el agregado. Después se comprueba la disposición de entrada del agregado:
  primero los flujos de entrada propios de la salida y al final el tap. Si es otra, se aborta sin
  procesar nada. Si la salida tiene micrófono, se marcan sus flujos de entrada como no usados
  (`kAudioDevicePropertyIOProcStreamUsage`) para no activarlo. Luego se arranca el IOProc.
- **Apagar / salir**: se detiene el IOProc y se destruyen el agregado y el tap. Antes de liberar el
  contexto se espera a que termine un bloque en curso. `willTerminate` apaga el motor. Si la app se
  cuelga o termina de golpe, coreaudiod destruye el tap y el agregado privados y el audio vuelve sin
  procesar.
- **Salida desconectada**: pausa ("esperando la salida"). Se reanuda sola cuando la salida vuelve.
- **Cambio de fs o de canales** (p. ej. auriculares Bluetooth que pasan de A2DP a HFP al usar el
  micrófono): se reconstruye todo con el formato nuevo.
- **Watchdog** (gotcha 8 de ParaEQ): si a los 5 s no ha habido llamadas al IOProc, se reinicia una vez.
  Si sigue sin haberlas, se desmonta y se muestra un error, en lugar de dejar el audio silenciado.
- Los listeners corren en colas propias, nunca en la principal (gotcha 1 de ParaEQ: deadlock). Los
  cambios de la lista de dispositivos tienen un retardo de 0,5 s para no reaccionar a nuestro propio
  agregado (gotcha 2).
- Mientras corre, una actividad `latencyCritical` evita que App Nap duerma los temporizadores.

## Permisos y firma
- `Info.plist`: `NSAudioCaptureUsageDescription` (texto en español). macOS muestra el aviso "Grabación
  de audio del sistema" la primera vez que se enciende el EQ avanzado. El permiso se gestiona en Ajustes
  del Sistema › Privacidad y seguridad › Grabación de pantalla y audio del sistema; la UI tiene un botón
  que abre esa sección.
- Entitlement `com.apple.security.device.audio-input` (`Resources/XMAudioStudio.entitlements`). Hace
  falta porque `build_app.sh` firma con runtime endurecido. La app no usa sandbox (compatibilidad con
  la Mac App Store no evaluada).
- El permiso de TCC va ligado a la identidad de firma. Con firma ad-hoc se vuelve a pedir tras cada
  compilación. Con "Apple Development" se conserva.
- Según ParaEQ (gotcha 11), un binario lanzado desde un proceso sin interfaz recibe silencio sin ningún
  error. Hay que abrir la `.app` normalmente (Finder u `open`).
- Si el permiso se deniega, el resultado puede ser un error al crear el tap (la UI lo muestra) o
  **silencio sin error**. Para el segundo caso la UI muestra el nivel de entrada y, tras 5 s sin señal,
  una pista hacia el permiso.

## Latencia (estimada, no medida)
- Limitador: 5 ms de anticipación (240 muestras a 48 kHz). Los biquads no añaden latencia de grupo
  apreciable fuera de las resonancias.
- A eso se suma el búfer de E/S del agregado. ParaEQ cita entre 5 y 10 ms en total para su ruta
  paramétrica.
- Bluetooth añade su propia latencia de códec, de un orden muy superior. Con vídeo, macOS compensa la
  latencia de la salida Bluetooth, pero no sabemos si también compensa la del agregado.
- El modo A/B "Original" pasa el audio sin la anticipación del limitador (unos 5 ms menos que
  "Ecualizado").

## Doble corrección
Si los audífonos tienen activo un perfil `measured`, su EQ ya aplica una corrección AutoEQ ajustada a
sus 10 bandas, y aplicar otra en el Mac la duplica. Hay tres protecciones:
1. "Aplicar la curva objetivo AutoEQ del perfil" pide confirmación. También ofrece apagar primero el EQ
   de los audífonos ("Original Sony").
2. Encender el EQ avanzado pide la misma confirmación si las bandas vienen de una curva AutoEQ
   (`correctionSource`).
3. Mientras ambos estén aplicados se muestra un aviso con dos acciones: apagar el EQ de los audífonos u
   omitir el DSP.

La confirmación se recuerda por pareja (perfil de los audífonos, perfil del DSP) solo durante la sesión.
Si el EQ de los audífonos no se puede leer, hay una nota informativa.

## Riesgos conocidos
1. **El tap por dispositivo no está probado en vivo.** ParaEQ solo verificó el tap global. Si la prueba
   manual da silencio o no procesa, existe el modo de diagnóstico
   `defaults write io.github.andru1989.xmaudiostudio dsp.tapScope systemWide`, con el diseño de ParaEQ.
   Ese modo solo corre si la salida elegida es la del sistema. Para volver:
   `defaults delete io.github.andru1989.xmaudiostudio dsp.tapScope`.
2. **`.mutedWhenTapped`**:
   - Un agregado bloqueado (watchdog) deja silencio hasta que actúe el watchdog.
   - Tras mucho crear y destruir taps, coreaudiod puede dejar de silenciar el original, y se oye audio
     doble (gotcha 10). Se arregla con `sudo killall coreaudiod` o reiniciando.
   - Por eso los taps solo se crean por acción del usuario o por cambios reales de dispositivo o formato.
3. **Realimentación**: se excluye nuestro propio proceso. Un proceso auxiliar nuestro que reprodujera
   audio sí se capturaría (hoy no existe ninguno).
4. **Salida con flujos de entrada** (interfaces USB, auriculares con micrófono expuestos como un solo
   dispositivo): se asume el orden "flujos del dispositivo, luego el tap" y se verifica. Si no coincide,
   no se procesa. Esto no está probado con hardware real.
5. **Bluetooth XM6**: el paso a HFP cambia fs y canales, y el motor se reinicia (hay un corte breve).
   Las apps con DRM u otros taps podrían saltarse el mezclador.
6. **Nivel**: un realce sube el nivel de verdad. El margen automático está activo por defecto y el
   limitador siempre actúa. Con el margen apagado y realces grandes, el limitador trabaja y puede
   oírse.

## Qué se verificó
- `swift test` (85 tests, todos pasan; 26 de ellos en `AudioDSPKitTests`):
  - bypass bit a bit, incluido tras el fundido;
  - ganancia unidad con filtros a 0 dB (error < 1e‑5, compensando el retardo del limitador);
  - seno en el centro de un peaking: ±6, +12 y +3,5 dB a 44,1/48/96 kHz dentro de ±0,1 dB;
  - la ganancia medida sigue la respuesta de los coeficientes;
  - margen automático;
  - bandas por canal, y flujos mono y de 6 canales;
  - |x| ≤ 1 con +24 dB y entradas NaN/±∞, y recuperación tras entrada no finita;
  - cambio de fs 48 → 96 kHz;
  - una actualización en vivo cambia realmente el audio (gotcha 13);
  - coeficientes finitos y estables en ~47 000 combinaciones de tipo, f, ganancia, Q y fs;
  - vectores RBJ compartidos;
  - conversión de buffers de Core Audio y el cuerpo completo del IOProc con buffers simulados;
  - **ninguna asignación de memoria en el render**: se mide con el hook `malloc_logger`, incluidas las
    recogidas de parámetros, los cambios de bypass y fs, y 240 bloques. El test comprueba antes que el
    hook detecta una asignación deliberada.
- `Scripts/build_app.sh` genera la `.app` universal firmada con runtime endurecido, el entitlement
  `com.apple.security.device.audio-input` y `NSAudioCaptureUsageDescription` (comprobado con
  `codesign -d --entitlements -` y `plutil`).
- UI: se renderizaron fuera de pantalla las pestañas ámbar (claro y oscuro) y azul en modo demo y en
  español. La sesión estaba bloqueada, así que no se pudo usar `screencapture`; el render se hizo con un
  arnés temporal que no se ha incluido en los commits. No se renderizaron el diálogo ni el aviso de doble
  corrección (necesitan un perfil `measured` activo en los audífonos).

**No verificado**:
- la captura en vivo y el aviso de permiso;
- el comportamiento real del tap por dispositivo;
- audio audible, latencia y CPU con música real;
- Bluetooth/HFP;
- desconexión y reconexión de la salida;
- el watchdog con un agregado real;
- `IOProcStreamUsage` con una salida que tenga micrófono.

## Prueba manual sugerida
1. Compilar con `Scripts/build_app.sh` (identidad estable) y abrir la app desde Finder.
2. Estudio de ecualización › pestaña "EQ avanzado". Elegir la salida (los XM6 o los altavoces) y
   añadir un peaking de +6 dB a 1 kHz. Reproducir música en esa salida y encender: debe aparecer el aviso
   de permiso.
3. Comprobar varias cosas:
   - el estado "Procesando … kHz" y el nivel de entrada;
   - que A/B cambia el sonido;
   - que mover la ganancia no produce clics;
   - que al apagar el audio sigue sonando sin procesar;
   - que Ajustes › Sonido no ha cambiado de salida.
4. Elegir una salida que no sea la del sistema: el audio de la salida del sistema no debe verse
   afectado.
5. Con los XM6 en Bluetooth, abrir una app que use el micrófono (paso a HFP): el motor debe reiniciarse
   o pausarse sin dejar silencio.
6. Aplicar a los audífonos un perfil AutoEQ medido y luego "Aplicar la curva objetivo AutoEQ del
   perfil": debe aparecer la confirmación de doble corrección.
7. Registros:
   `log stream --level info --predicate 'subsystem == "io.github.andru1989.xmaudiostudio" AND category == "dsp"'`.
