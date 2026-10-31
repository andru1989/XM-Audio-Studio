# ADR-0012 — Pausar el dispositivo que pierde el audio

Fecha: 2026-10-31 · Estado: Aceptada (simulación; pendiente de hardware) · Complementa
[ADR-0011](ADR-0011-newest-playback-wins.md).

## Contexto
Con ADR-0011 el dispositivo que empieza a sonar se queda con el audio, pero el otro puede seguir
reproduciendo en silencio (consume batería, avanza la lista, y al volver el audio está en otro punto).
El propietario pidió que, al detectar que suena en el dispositivo 1, el contenido del dispositivo 2 se
pause automáticamente.

## Decisión
1. Toda intención que mueve el audio (reclamo automático, «Reproducir aquí», «Volver y reanudar») envía
   primero **pausa** (`a4 01 00 01`) y después el cambio de fuente (`3c 01 <MAC>`). La pausa la reenvían
   los audífonos por AVRCP al dueño actual del derecho de reproducción, es decir, al dispositivo que va a
   perder el audio; por eso debe ir **antes** del cambio.
2. No se envía si el dueño es el propio destino, si ya está en pausa o detenido, durante una llamada, o si
   los audífonos dicen que sus controles están deshabilitados. Con el estado sin informar sí se envía
   (pausar un reproductor parado no hace nada).
3. Si los audífonos **rechazan** el cambio (`3d 01` ≠ éxito), o no lo confirman y la lista muestra que no
   ocurrió, se envía **reproducir** para que el dispositivo pausado siga sonando.
4. Misma regla en ambas plataformas: `PlaybackSourceAdvisor.pauseBeforeSwitch` (Kotlin y Swift).

## Consecuencias
- Solo suena un dispositivo a la vez y el que pierde el audio queda en pausa donde iba.
- Depende de que el reproductor del otro dispositivo atienda el AVRCP de los audífonos (Deezer, Spotify,
  Música, Safari… lo hacen); un reproductor que lo ignore seguirá sonando en silencio.
- Si el XM6 ya cambió solo de fuente antes de que la app lea la lista, el otro dispositivo ya no es el
  dueño y la pausa no se le puede enviar por los audífonos; normalmente en ese caso ya estaba parado.
