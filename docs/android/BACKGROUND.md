# Android: mosaico de Ajustes rápidos y widget en segundo plano

Etapa 6. Código: `android/app/src/main/kotlin/.../platform/`. Política del canal:
[ADR-0003](../decisions/ADR-0003-on-demand-control-channel.md) y
[SESSION-LIFECYCLE.md](../bluetooth/SESSION-LIFECYCLE.md).

## Sin servicio en primer plano

El mosaico (`NoiseControlTileService`), el widget (`NoiseWidgetProvider`) y sus botones
(`NoiseControlReceiver`, no exportado) usan **la misma `HeadphoneSession` del proceso**
(`XmAudioStudioApp.container.session`). No hay servicio en primer plano ni sondeo periódico
(`updatePeriodMillis = 0`). Si el proceso arranca por el mosaico o el widget, `AppContainer`
selecciona los audífonos guardados (o el XM6 simulado en modo demostración) sin abrir el canal.

## Cómo adquieren y liberan el canal

1. Un toque es **actividad del usuario**: si el estado no está en vivo, `NoiseControl` llama a
   `session.connect()` (también recupera el control tras `YIELDED` o `FAILED`) y **espera el
   estado real** antes de decidir (regla 3). El mosaico calcula el siguiente modo
   (Cancelación de ruido → Ambiente → Desactivado) a partir de ese estado, y la escritura conserva
   "enfocar en la voz" y el nivel de ambiente que informan los audífonos. Si el modo no llega, no se
   escribe nada.
2. La intención (`DeviceCommand.SetAmbientSound`) pasa por la cola de la sesión como cualquier
   comando de la app (fusión, stop-and-wait, ACK).
3. **Liberación a los 20 s** de la última actividad, porque no hay ninguna superficie visible. El
   tráfico de fondo no prolonga la gracia. Con "Liberar control tras 20 s sin uso" desactivado, el
   canal queda abierto mientras viva el proceso.
4. Si otro dispositivo (Sound Connect, el Mac) toma el control, los audífonos cierran el canal: la
   sesión pasa a `YIELDED` y **no reconecta sola**. El mosaico muestra "En otro dispositivo" y el
   widget "En uso por otro dispositivo"; el siguiente toque recupera el control.
5. El receptor del widget usa `goAsync()` solo hasta que la intención se escribe más ~0,75 s para el
   ACK (como máximo 9 s); la sesión continúa después.

## Qué muestran

- Mientras vive el proceso, `PlatformSync` (en `AppContainer`) reduce cada `snapshot` a un
  `NoiseControlStatus` y, si cambió, lo envía a los widgets con `AppWidgetManager`. El mosaico no es
  "activo": Android lo enlaza al abrir Ajustes rápidos y en ese rato sigue el mismo estado.
- **Último estado conocido**: `LastKnownStore` guarda en `SharedPreferences` (`platform.xml`,
  clave `lastKnownState`) un JSON mínimo: dirección, modelo, modo, batería, carga y marca de tiempo.
  Se escribe cuando cambia el estado en vivo y otra vez cuando deja de estarlo (con esa hora). Solo
  se muestra si es de los audífonos seleccionados y **nunca se escribe en los audífonos**.
- El widget muestra la hora absoluta ("Último estado conocido · 13:05"), porque puede seguir en
  pantalla mucho después de que muera el proceso.
- No disponible: sin audífonos elegidos, modelo sin adaptador o, fuera del modo demostración, sin el
  permiso `BLUETOOTH_CONNECT` (solo la app puede pedirlo). El mosaico queda en `STATE_UNAVAILABLE` y
  los botones del widget abren la app.

## Límites de Android

- **El proceso puede morir** en cualquier momento tras la liberación. El launcher conserva las
  últimas vistas enviadas (que para entonces dicen "último estado conocido"); el siguiente toque crea
  el proceso, lee el estado real y actualiza.
- **Congelación de procesos en caché** (Android 12+, `use_freezer`): un proceso sin actividad puede
  congelarse unos 10 s después de pasar a caché. Si eso ocurre con el canal abierto, el temporizador
  de 20 s no corre hasta que el proceso se descongela o muere (al morir, el sistema cierra el
  socket RFCOMM); mientras tanto, otro dispositivo puede encontrar el canal ocupado. En el emulador
  (API 35, `freeze_debounce_timeout=10000`), con el proceso creado solo por un toque en el widget,
  el canal se liberó a los ~20 s y el proceso se congeló después (~30 s). No está garantizado en
  todos los fabricantes: es candidato a prueba con hardware.
- Tras "Forzar detención", Android cancela los `PendingIntent` del widget; vuelven a funcionar en
  cuanto el proceso arranca de nuevo (abrir Ajustes rápidos o la app vuelve a pintar el widget).
- En modo demostración el simulador vive dentro del proceso: si el proceso muere, el XM6 simulado
  vuelve a su estado inicial (Cancelación, 72 %). Esto es propio de la demo.
