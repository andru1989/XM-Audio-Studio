# ADR-0011 — Gana el dispositivo que empieza a sonar (seguimiento automático por defecto)

Fecha: 2026-10-31 · Estado: Aceptada (simulación; pendiente de hardware) · Reemplaza el punto 6 de
[ADR-0010](ADR-0010-playback-source-tracking.md) y la regla "sin servicio en primer plano" de
`docs/android/BACKGROUND.md` mientras esta función esté activa.

## Contexto
ADR-0010 dejó el seguimiento automático apagado por defecto, solo con la app abierta en Android, y sin
quitar nunca los controles a un dispositivo que reproduce. El propietario pidió lo contrario: que **ambos
sistemas detecten solos cuando el otro empieza a sonar y se intercambien** para poder escuchar, sin abrir
la app. Con dos dispositivos conectados, el XM6 a veces no cede el audio (p. ej. porque el otro mantiene
abierto un flujo, aunque sea silencioso), y entonces el que acaba de empezar no se oye.

## Decisión
1. **Cada dispositivo reclama el audio cuando él empieza a sonar** ("gana el más reciente"). Lo detecta su
   propia app con una señal local y envía una intención `ClaimPlayback` / `claimPlaybackHere()` que se
   resuelve **contra la lista leída tras conectar**: no escribe nada si ya tiene el derecho de
   reproducción, si no está en la lista o no está conectado, si hay un cambio pendiente o una llamada.
   No hace falta que la app del otro dispositivo esté vigilando.
2. **Un reclamo por cada inicio**, tras 2 s sonando (una notificación nunca cuenta), y como máximo una
   acción automática cada 15 s por dispositivo. Si los dos siguen sonando no hay ping-pong: cada uno solo
   actúa en su propio inicio. A diferencia de ADR-0010, el reclamo **sí** quita el audio a un dispositivo
   que reproduce: es lo que el usuario acaba de hacer en este. El aviso y el cambio "si el otro está
   parado" de ADR-0010 siguen igual.
3. **Activado por defecto** ("Seguir la reproducción automáticamente"), en ambas apps.
4. **Segundo plano:**
   - macOS: la app de la barra de menús escucha CoreAudio y conecta el canal de control solo para
     reclamar (aunque la liberación esté activa); lo libera a los 20 s como siempre. "Empezar a sonar"
     = una app con flujo de salida hacia los audífonos según los objetos de proceso de CoreAudio; los
     sonidos del sistema, notificaciones, voz y procesos sin bundle no cuentan.
   - Android: un **servicio en primer plano** (`connectedDevice`, con notificación discreta) escucha
     `AudioPlaybackCallback` mientras la opción esté activa y se reinicia tras el arranque. No mantiene el
     canal abierto: lo abre al reclamar y la sesión lo libera a los 20 s.
5. Reclamar abre el único canal de control que permiten los audífonos, así que la app del otro
   dispositivo (o Sony Sound Connect) cede el control en ese momento; cada app lo recupera en su
   siguiente uso, como ya ocurría (ADR-0003).

## Consecuencias
- Cambiar de dispositivo es automático en ambos sentidos: empezar a reproducir en uno basta.
- Cada inicio de reproducción cuesta una conexión breve del canal de control (lectura + cambio si hace
  falta); si el dispositivo ya tenía el audio no se escribe nada.
- Un flujo que se abre en silencio (una pestaña que prepara audio) cuenta como inicio en macOS; si molesta,
  la opción se puede apagar en Ajustes.
- En Android hay una notificación permanente mientras la opción esté activa (requisito del sistema).
