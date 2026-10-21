# Otros modelos: qué está implementado y con qué evidencia

Actualizado: 2026-10-21. **Ninguno de estos modelos se ha probado con hardware en este proyecto.** Todo lo
de abajo está construido solo con hechos documentados: tablas de mos9527/SonyHeadphonesClient (MIT,
generadas de los metadatos de Sound Connect), sus capturas reales (WH-1000XM3 fw 4.5.2 y WF-1000XM5 fw 6.1.0)
y las notas de hardware de marconvcm/sony-device-center (MIT). Gadgetbridge (AGPL) solo como contraste de
hechos; no se copió código de nadie. Bytes exactos: [`PROTOCOL.md` §5](../../shared/protocol-spec/PROTOCOL.md).
Investigación: [`protocol-research.md`](../research/protocol-research.md) §6–§10.

## Dónde funciona

| Plataforma | XM6 | XM5 / XM4 / XM3 / XM2 | MDR-1000X |
|---|---|---|---|
| Android | Adaptador `Xm6Adapter` | `Xm5Adapter`, `MdrV1Adapter` (la app los usa; marcados "sin probar con hardware") | Sin adaptador: no se envía ningún byte |
| macOS | `HeadphonesController` | Solo códecs (`Xm5Commands`, `MdrV1Commands`) y tests de conformidad; el controlador **no** los usa | — |

Niveles del catálogo (`shared/protocol-spec/models.json`, iguales en Kotlin y Swift):
**pending-hardware** = implementado desde referencias con evidencia para ese modelo o su capa exacta;
**experimental** = implementado por inferencia o con un riesgo abierto conocido;
**planned** = el modelo lo tiene, el adaptador no lo implementa (no se envía nada).

## WH-1000XM5 (MDR v2) — `Xm5Adapter`

| Función | Nivel | Evidencia | Falta |
|---|---|---|---|
| Batería `22 00` | pending-hardware | Mismo comando que el XM6 (verificado por upstream); sony-device-center lo informa en XM5 fw 2.3.1 | Captura de un WH-1000XM5 |
| NC / ambiente / nivel / voz (`0x17`, 7 B) | pending-hardware | Captura WF-1000XM5 `67 17 01 00 01 00 14`; setter de sony-device-center en XM5 | Captura del WH-1000XM5 y confirmar que `66 17` responde |
| EQ curva + Clear Bass (`0x00`, +10) | pending-hardware | Captura WF-1000XM5 `57 00 a0 06 14 0b 0a 0d 09 0c`; escrituras de sony-device-center en XM5 | Leer `5A 00` (frecuencias) y `50 00 01` en el dispositivo |
| EQ presets | experimental | Comando `58 00 id 00` de sony-device-center; **lista de presets supuesta** (la del XM3); mos9527: "no way to choose presets" | Respuesta real de `50 00 01` |
| Speak-to-Chat + config. (`0x0C`) | pending-hardware | Capturas WF-1000XM5 `F7 0C 01 01`, `FB 0C 00 00`; igual que XM6 | Prueba en WH-1000XM5 |
| Apagado automático, pausa al quitarse | planned | El informe solo tiene capturas del WF-1000XM5. sony-device-center dice que `26/28 05` funciona en XM5 (incluidos tiempos 5/30/60/180 min que el modelo de la app aún no representa) | Captura del WH-1000XM5 y ampliar `AutomaticPowerOffMode` |
| Modos de escucha, multipunto | — (no catalogados) | Sin documentación para el XM5 | — |

## WH-1000XM4 (MDR v1) — `MdrV1Adapter`

| Función | Nivel | Evidencia | Falta |
|---|---|---|---|
| Init de 4 bytes, `04 01` / `04 02` | — | Captura XM3 (mismo protocolo v1) | — |
| Batería `10 00` → `11 00 nivel carga` | pending-hardware | Captura XM3 `11 00 64 00`; sony-device-center verificó batería en XM4 fw 3.0.1 | Captura XM4 |
| NC / ambiente / nivel / voz (tipo `0x02`) | pending-hardware | Capturas XM3 RET/NTFY; lectura verificada en XM4 (sony-device-center); setter `68 02 11 01 …` usado por tres clientes MIT | Confirmar escritura en XM4 |
| EQ curva + Clear Bass, presets (tipo `0x01`) | pending-hardware | Captura XM3 de lectura y capacidad; "EQ + Clear Bass" verificado en XM4 (sony-device-center) | Capacidad `50 01 01` real del XM4 |
| Speak-to-Chat (`F6/F8 05`) y opciones (`FA/FC 05`) | experimental | On/off verificado en XM4 por sony-device-center (no invertido, `01` = on); la lectura `FA 05`/`FB 05` sale solo de las tablas de Sony. Al activar se reenvían las opciones (sin ellas una sesión del XM4 no se cierra nunca, según sony-device-center) | Captura de `FB 05` y de la activación |
| Pausa al quitarse | planned | `FC 03 00 v` solo en tablas; nadie lo informa en hardware | Captura |
| Apagado automático | planned | Ver XM3; el XM4 tiene además "al quitarse" | Ampliar el modelo de datos |

## WH-1000XM3 (MDR v1) — `MdrV1Adapter`

| Función | Nivel | Evidencia | Falta |
|---|---|---|---|
| Batería, NC/ambiente/nivel/voz | pending-hardware | Captura completa fw 4.5.2 (mos9527) + cambios de NC confirmados por notificación; sony-device-center: XM3 del mantenedor | Prueba propia |
| EQ presets y curva + Clear Bass | experimental | Lectura, capacidad y frecuencias capturadas (frecuencias **verificadas**: 400/1k/2.5k/6.3k/16k). Riesgo: al cambiar el preset el XM3 envió `ALERT_NTFY_PARAM` (`99 01 01 01`, cambio de modo de conexión) y mos9527 respondió NEGATIVE; esta app **no responde alertas** ni se suscribe a ellas | Ver qué pasa sin respuesta; si hace falta, implementar la respuesta de alerta v1 con captura |
| Apagado automático | planned | Captura `F7 04 01 00 00`; mos9527 escribe `FC 04 01 activo seleccionado` (5/30/60/180 min, off), confirmado solo con ACK | Ampliar `AutomaticPowerOffMode` (el XM6 solo acepta 0x10/0x11) |
| Speak-to-Chat, pausa al quitarse, multipunto | — | El XM3 no los tiene | — |

## WH-1000XM2 (MDR v1) — `MdrV1Adapter`

Todo **experimental**: no existe ninguna captura pública del XM2. Se asume la misma tabla v1 que el XM3
(sony-device-center lo clasifica como v1 sin probar). Implementado: batería, NC/ambiente/nivel/voz, EQ presets
y curva + Clear Bass. Si el init no devuelve 4 bytes la sesión aborta sin escribir. Falta: cualquier captura.

## MDR-1000X

Sin adaptador ni capturas (sony-device-center: "Known broken"). La app no le envía ningún byte.

## Qué capturar primero (por valor)

1. **WH-1000XM4**: arranque completo (`07 00` lista de funciones, `51 01`, `5B 01`, `FB 05`), activar
   Speak-to-Chat y cambiar opciones desde Sound Connect, mover NC/ambiente y EQ.
2. **WH-1000XM5**: `07 00` (T1/T2), `66 17`, `56 00`, `50 00 01`, `5A 00`, `26 05`, `F6 01` y sus escrituras.
3. **WH-1000XM3**: cambio de preset desde esta app sin responder la alerta.
4. **WH-1000XM2** y **MDR-1000X**: cualquier sesión.

Una captura HCI de Android (btsnoop) o el registro de protocolo de la app (Ajustes → Diagnóstico) sirve.

## Líneas sugeridas para FEATURE-STATUS.md

(Este documento no edita FEATURE-STATUS.md; quien lo mantenga puede usar esto.)

| Modelo | Detección por nombre | Catálogo de capacidades | Adaptador | Estado |
|---|---|---|---|---|
| WH-1000XM5 | Sí | Sí (5 bandas + Clear Bass) | Android `Xm5Adapter` (macOS: solo códec) | PEND batería, NC/ambiente, EQ curva, STC · EXP presets EQ · PLAN apagado/pausa |
| WH-1000XM4 | Sí | Sí | Android `MdrV1Adapter` (macOS: solo códec) | PEND batería, NC/ambiente, EQ · EXP Speak-to-Chat · PLAN apagado/pausa |
| WH-1000XM3 | Sí | Sí (frecuencias verificadas) | Android `MdrV1Adapter` (macOS: solo códec) | PEND batería, NC/ambiente · EXP EQ (alerta sin responder) · PLAN apagado |
| WH-1000XM2 | Sí | Sí | Android `MdrV1Adapter` (macOS: solo códec) | EXP todo (sin capturas) |
| MDR-1000X | Sí | Parcial | No | PLAN (sin capturas públicas) |
