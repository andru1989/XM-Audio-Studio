# Especificación del protocolo Sony MDR implementado

Fuente normativa para **ambas** apps. Todo lo que aquí figura está cubierto por
`shared/protocol-fixtures/*.json`. Investigación completa y evidencias en
[`docs/research/protocol-research.md`](../../docs/research/protocol-research.md).

Leyenda de evidencia: **HW** = confirmado con captura/escritura en un XM6 real (upstream o terceros
citados); **REF** = de referencias (mos9527 MIT, Gadgetbridge solo como hecho); **?** = sin verificar.

## 1. Transporte

| Elemento | Valor | Evidencia |
|---|---|---|
| Canal | RFCOMM sobre Bluetooth Classic, descubierto por SDP | HW |
| UUID v2 (XM5, XM6) | `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` | HW |
| UUID v1 (MDR-1000X…XM4) | `96CC203E-5068-46AD-B32D-E316F5E069BA` | REF |
| Sesiones simultáneas | **Una** (el audio multipunto es independiente) | HW |

La familia de protocolo la fija el UUID al que se conecta; el init la confirma.

## 2. Frame

```
3E | escape( TYPE(1) SEQ(1) LEN(4, big-endian) PAYLOAD(LEN) CHECKSUM(1) ) | 3C
```
- `CHECKSUM` = suma de TYPE…PAYLOAD, módulo 256 (sin la cabecera).
- Escapado: `3C`, `3D`, `3E` dentro del cuerpo → `3D` seguido de `byte & 0xEF` (`2C`, `2D`, `2E`).
- `TYPE`: `01` ACK · `0C` comando tabla 1 · `0E` comando tabla 2 (periféricos/multipunto).
- Secuencia de 1 bit (stop-and-wait). El host envía con su `seq` actual; al recibir el ACK del
  dispositivo adopta el `seq` del ACK. Cada mensaje del dispositivo se confirma con ACK `seq = 1 − seq_recibido`.
- Un solo mensaje en vuelo; timeout de ACK 3 s → se descarta y se continúa.
- Los dispositivos a veces envían la respuesta antes que el ACK, o confirman sin responder nunca.

## 3. Arranque de sesión (orden en ambas apps)

1. Init `00 00` (reintento cada 1,25 s, máx. 2). Respuesta `01 …`: 4 bytes = v1, 8 bytes = v2.
   Si la familia no es la esperada para el modelo → abortar sin escribir nada.
2. Lecturas: `04 01` modelo, `04 02` firmware, `66 15`/`66 17`/`66 19` NC/ambiente, `22 00`
   batería, `F6 0C` speak-to-chat, `FA 0C` config. STC, `26 05` apagado automático, `F6 01` pausa al
   quitarse, `56 04` y `56 00` EQ, `50 04 01` capacidad EQ, `E6 09` BGM, `E6 04` cine, tabla 2 `36 02` multipunto.
3. Solo después se envían intenciones del usuario.

## 4. Comandos WH-1000XM6 (MDR v2, tabla 1 salvo indicación)

| Función | GET | RET / NTFY | SET | Notas | Evid. |
|---|---|---|---|---|---|
| Modelo | `04 01` | `05 01 len ASCII` | — | igual en v1/v2 | REF |
| Firmware | `04 02` | `05 02 len ASCII` | — | | REF |
| Batería | `22 00` | `23/25 00 nivel carga` · `23/25 08 nivel carga umbral` | — | tipo 08 posible en XM6 (?) | HW (00) |
| NC/Ambiente fw 3.x | `66 19` | `67/69 19 upd efecto modo voz nivel autoAmb sensib` (9 B) | se escribe como 0x17 ↓ | `upd=0` en `0x17` = marcador vacío (fw 3.0.0) | HW |
| NC/Ambiente | `66 17` | `67/69 17 upd efecto modo voz nivel` (7 B; 8 B con byte de viento) | `68 17 01 efecto modo voz nivel` | nivel 1–20 al escribir; `upd` 00 durante arrastre | HW |
| EQ | `56 04` | `57/59 04 preset nBandas bandas…` | preset: `58 04 id 00` · curva: `58 04 A0 0A g+6 ×10` | 10 bandas, −6…+6, `wire = g + 6`; `56 00` ignorado | HW |
| Capacidad EQ | `50 04 01` | `51 04 nBandas nPasos nPresets (id lenNombre nombre)*` | — | confirma 10/13 en hardware (?) | REF |
| Música de fondo | `E6 09` | `E7/E9 09 off sala` | `E8 09 off sala` | **invertido: 00 = activado**; sala 00/01/02 | REF |
| Cine (upmix) | `E6 04` | `E7/E9 04 off` | `E8 04 off` | invertido | REF |
| Speak-to-Chat | `F6 0C` | `F7/F9 0C off 01` | `F8 0C off 01` | invertido | REF |
| Config. STC | `FA 0C` | `FB/FD 0C sens tiempo` | `FC 0C sens tiempo` | sens 00 auto/01 alta/02 baja; tiempo 00–03 | REF |
| Apagado automático | `26 05` | `27/29 05 modo 00` | `28 05 modo 00` | solo `10` (al quitarse) y `11` (nunca); **no** usar `F8 05` (palabra de activación) | HW |
| Pausa al quitarse | `F6 01` | `F7/F9 01 off` | `F8 01 off` | invertido; `26 01` se confirma pero no responde | HW |
| Lista multipunto (T2) | `36 02` | `37/39 02 n (MAC17 estado COD3 lenNombre nombre)* estadoReproducción` | — | | HW |
| Cambiar fuente (T2) | — | — | `3C 01 MAC17` | | REF |

Presets EQ XM6: `00` Off, `30` Heavy, `31` Clear, `32` Hard, `33` Soft, `A0` Custom, `A1+` ajustes de
usuario. Con BGM o Cine activos el EQ queda deshabilitado por el firmware.

## 5. Otras generaciones (adaptadores implementados, **sin probar en hardware**)

Solo Android usa estos adaptadores (`Xm5Adapter`, `MdrV1Adapter`); macOS tiene los mismos códecs
(`Xm5Commands`, `MdrV1Commands`) cubiertos por los fixtures, pero su controlador sigue hablando solo con
el XM6. Fixtures: `events-xm5.json`/`commands-xm5.json`, `events-v1.json`/`commands-v1.json`.
Evidencia y lo que falta por modelo: [`docs/compatibility/OTHER-MODELS.md`](../../docs/compatibility/OTHER-MODELS.md).

Leyenda adicional: **CAP** = la misma disposición aparece en una captura real de un modelo hermano
(XM3 fw 4.5.2 o WF-1000XM5 fw 6.1.0, pruebas de mos9527, MIT); **REF‑HW** = un cliente MIT informa que
funciona en ese modelo (sony-device-center: XM4 fw 3.0.1, XM5 fw 2.3.1), sin captura publicada.

### 5.1 Ecualizador de 6 bytes (Clear Bass + 5 bandas), XM3/XM4 (v1) y XM5 (v2)

Los opcodes EQEBB `56/57/58/59` coinciden en v1 y v2; solo cambia el tipo: **v1 `0x01`**, **XM5 `0x00`**.

| Mensaje | Bytes | Evid. |
|---|---|---|
| GET | `56 <tipo>` | CAP |
| RET / NTFY | `57/59 <tipo> <preset> 06 <ClearBass> <400> <1k> <2.5k> <6.3k> <16k>`; cada byte = pasos + 10 (−10…+10) | CAP (XM3 `57 01 00 06 0a…`, WF-XM5 `57 00 a0 06 14 0b 0a 0d 09 0c`) |
| Elegir preset | `58 <tipo> <preset> 00` (sin bandas) | REF‑HW |
| Curva | `58 <tipo> A0 06 <ClearBass+10> <banda+10 ×5>`; se rechaza (no se envía) si no hay exactamente 5 bandas | REF‑HW |
| Capacidad | `50 <tipo> 01` → `51 <tipo> 06 15 <n> (<id> <len> <nombre>)*` (6 valores, 21 pasos) | CAP (XM3) |

Frecuencias 400/1k/2.5k/6.3k/16k Hz: leídas del propio XM3 (`5B 01 06 10 0000 01 0190 01 03E8 01 09C4 01 189C 01 3E80`);
supuestas en XM4/XM5. Presets: `00` Off, `10`–`17` Bright…Speech, `A0` Manual, `A1`/`A2` ajustes de usuario (lista
leída del XM3; supuesta en XM4/XM5). Clear Bass ausente en el comando → la app reescribe el valor leído.

### 5.2 WH-1000XM5 (MDR v2, tabla 1)

| Función | GET | RET / NTFY | SET | Evid. |
|---|---|---|---|---|
| Batería | `22 00` | `23/25 00 nivel carga` (también `08`) | — | REF‑HW (igual que XM6) |
| NC/Ambiente | `66 17` | `67/69 17 upd efecto modo voz nivel` — **exactamente 7 B** | `68 17 01 efecto modo voz nivel(1–20)` | CAP (WF-XM5 `67 17 01 00 01 00 14`) · REF‑HW |
| EQ | §5.1, tipo `0x00` | | | CAP · REF‑HW |
| Speak-to-Chat | `F6 0C` | `F7/F9 0C off 01` (invertido) | `F8 0C off 01` | CAP (WF-XM5 `F7 0C 01 01`) · REF‑HW |
| Config. STC | `FA 0C` | `FB/FD 0C sens tiempo` | `FC 0C sens tiempo` | CAP (WF-XM5 `FB 0C 00 00`) |

Lecturas tras conectar: `04 01`, `04 02`, `66 17`, `22 00`, `F6 0C`, `FA 0C`, `56 00`, `50 00 01`.
**No implementado** (no documentado para el WH-1000XM5): apagado automático, pausa al quitarse, modos de
escucha, multipunto. El decodificador ignora `0x19`, `0x17` de 8 bytes y EQ `0x04`.

### 5.3 MDR v1: WH-1000XM4 / XM3 / XM2 (tabla 1, tipo de frame `0C`)

**v1 `0x22` = COMMON_SET_POWER_OFF**: ningún constructor lo produce y el adaptador descarta incluso un
`Raw` de desarrollo que empiece por `22`. Polaridad v1: `01` = activado. Init: respuesta de **4 bytes**
(`01 00 40 10` en el XM3 fw 4.5.2); si llega una de 8 bytes la sesión aborta antes de escribir.

| Función | GET | RET / NTFY | SET | Evid. |
|---|---|---|---|---|
| Batería | `10 00` | `11/13 00 nivel carga` (carga `01`; `F0` = desconocido) | — | CAP (XM3 `11 00 64 00`) |
| NC/Ambiente | `66 02` | `67/69 02 efecto tipoNC valorNC tipoASM asmId nivel` (8 B) | `68 02 <00 off / 11 on> 01 <02 NC / 00 ambiente> 01 <voz> <nivel; 0 en NC>` | CAP (XM3 RET/NTFY) · REF‑HW (setter de sony-device-center/Plutoberth/mos9527) |
| EQ | §5.1, tipo `0x01` | | | CAP · REF‑HW (XM4) |
| Speak-to-Chat (solo XM4) | `F6 05` | `F7 05 00 v` · `F9 05 01 v`; `xx = 02` (sesión activa) ⇒ activado | `F8 05 01 <00/01>` | REF‑HW (on/off en XM4) |
| Config. STC (solo XM4) | `FA 05` | `FB/FD 05 00 sens vozEnfocada tiempo` | `FC 05 00 sens vozEnfocada tiempo` | REF (tablas); al activar se reenvía primero la config. leída (o Auto/no/Estándar) como hace sony-device-center |

Campos NC v1: efecto `00` off, `01` on, `10` ajustando (no se toma como confirmado), `11` ajuste terminado;
valorNC `00` = ambiente, `01` single (reducción de viento, se muestra como NC), `02` dual = NC; asmId `01` = voz.
Lecturas tras conectar: `04 01`, `04 02`, `10 00`, `66 02`, `56 01`, `50 01 01` (+ `F6 05`, `FA 05` en el XM4).
**No implementado**: apagado automático (`F6/F7 04`, SET `FC 04 01 activo seleccionado`; capturado en el XM3
pero el modelo de la app solo admite 0x10/0x11), pausa al quitarse (`FC 03 00 v`, sin evidencia en hardware),
tabla 2 (guía de voz, emparejamientos), VPT/posición de sonido.

### 5.4 MDR-1000X

Sin capturas públicas ni adaptador: la app no le envía ningún byte.

## 6. Prohibido

Comandos de actualización de firmware, restablecimiento de fábrica, borrado de emparejamientos,
modos de prueba (`68 40`), y cualquier SET no listado arriba.
