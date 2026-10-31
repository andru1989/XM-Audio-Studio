# ADR-0010 — Dónde suena: seguimiento de la fuente multipunto

Fecha: 2026-10-31 · Estado: Aceptada (simulación; pendiente de hardware)

## Contexto
Con el WH-1000XM6 conectado a dos dispositivos (p. ej. Mac + teléfono), la app oficial apenas dice
dónde suena el audio. El propietario observa dos fallos que solo ocurren con los dos conectados:
no puede pausar desde el teléfono, y la música del teléfono se detiene al salir de Deezer y solo vuelve
al abrir la app de nuevo. Ambos encajan con el **derecho de reproducción** de los audífonos (el
dispositivo al que van sus botones y el AVRCP play/pausa, `playbackrightDevice` en la lista `37 02`)
apuntando a un dispositivo distinto del que suena, o pasando a otro dispositivo y pausando el primero.
Hasta ahora ambas apps leían la lista una vez al conectar y solo se actualizaban si los audífonos
enviaban una notificación, cosa que no siempre hacen (mos9527: "track changes are not always notified").

## Decisión
1. **Más señales del protocolo, todas documentadas (mos9527, MIT; fixtures en ambas suites):**
   estado de reproducción `a2/a3/a5 01`, pista `a6/a7/a9 01`, resultado del cambio de fuente `3d 01`
   (tabla 2) y controles `a4 01 00 <ctl>`. Se leen tras cada conexión junto a la lista.
2. **Refresco rápido y barato:** mientras haya una ventana/pantalla visible se releen lista, estado y
   pista cada 3 s (tres tramas pequeñas), sin contar como actividad (no retrasan la liberación del canal)
   y sin apilarse. Además se relee al instante cuando cambia el audio local, al volver a la app y cuando
   cambia el dueño del derecho. Nunca se abre el canal solo para esto.
3. **Señal local:** macOS usa CoreAudio (`kAudioDevicePropertyDeviceIsRunningSomewhere` sobre la salida
   Bluetooth de los audífonos, localizada por su dirección en el UID); Android usa
   `AudioPlaybackCallback` + `isMusicActive` con salida A2DP/LE. "Este dispositivo" en la lista se
   reconoce por dirección Bluetooth (macOS) o por nombre único (Android oculta su dirección).
4. **Cambio de fuente sin parpadeo:** el cambio se muestra al instante; una lista leída antes de que
   los audífonos terminen no lo revierte; `3d 01` lo confirma o lo rechaza (con motivo: llamada, sin A2DP,
   asistente de voz). Sin respuesta en 5 s, manda la lista real.
5. **Interrupción:** si el derecho pasa, sin que el usuario lo pida aquí, de un dispositivo que estaba
   *reproduciendo* a otro, y la música queda parada, se recuerda como interrupción y se ofrece
   "Volver a X y reanudar" (cambio de fuente + play cuando `3d 01` confirma).
6. **Seguimiento automático opcional** ("Seguir la reproducción automáticamente", **apagado por
   defecto** porque escribe sin un clic): lleva los controles a este dispositivo cuando lleva 2 s
   enviando audio y el dueño actual no reproduce, y recupera la música interrumpida tras 2 s. Nunca quita
   los controles a un dispositivo que reproduce, nunca durante una llamada ni con un cambio pendiente, y
   como máximo una acción automática cada 15 s (no hay ping-pong entre dispositivos). En macOS, con la
   liberación del canal desactivada, vigila también sin ventanas abiertas.

Las reglas viven en código compartido por plataforma con los mismos casos de prueba:
`PlaybackSourceAdvisor` / `HeadphoneState` (Kotlin) y `PlaybackSourceAdvisor` / `PlaybackSourceState` (Swift).

## Consecuencias
- Detectar el cambio cuesta ≤ 3 s con la app visible aunque los audífonos no notifiquen.
- El estado de reproducción que informan los audífonos se interpreta como el del dueño del derecho; no
  está verificado en un XM6 (sí capturado en WF-1000XM5 y WF-LC900). Por eso la interrupción exige
  "reproduciendo antes y parado después", y el seguimiento es opcional.
- Android solo actúa con la app en primer plano (no hay servicio en segundo plano para no retener el
  único canal de control que permiten los audífonos).
- macOS considera "enviando audio" un flujo abierto aunque sea silencioso (apps que mantienen la salida
  abierta); por eso nunca se quitan los controles a un dispositivo que reproduce.
