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

## 5. Diferencias por generación (para adaptadores futuros)

- **XM5** (v2): NC `0x17`; EQ tipo `0x00`, 6 bytes `[ClearBass, 400, 1k, 2.5k, 6.3k, 16k]`, `wire = g + 10`, ±10.
- **XM2/XM3/XM4** (v1): tablas de opcodes y polaridad **distintas** (v1: `01` = activado).
  **v1 `0x22` = apagar**. Batería `10 00`. EQ tipo `0x01`, 6 bytes, ±10.
- **MDR-1000X**: sin capturas públicas.

## 6. Prohibido

Comandos de actualización de firmware, restablecimiento de fábrica, borrado de emparejamientos,
modos de prueba (`68 40`), y cualquier SET no listado arriba.
