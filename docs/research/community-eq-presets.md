# Presets de ecualización de la comunidad (Sony 1000X)

> Investigación del 2026-10-23. Valores listos para el dispositivo en
> [`community-eq-shortlist.json`](community-eq-shortlist.json) (26 presets: XM6 8, XM5 8, XM4 6, XM3 4).
> En la app (perfiles incluidos del XM6, con crédito en `source` y en las notas): Singapore Spring como
> «V brillante · Loud and Wireless», Super* Review vídeo («Claridad») y Neutral («Neutro»), y Headphone
> Corner Harman. El resto sigue pendiente de elegir.

## Escala del XM6
La app Sound Connect muestra el EQ del XM6 en **−6…+6, pasos enteros, 10 bandas (31 Hz–16 kHz)**, igual
que nuestro protocolo → los valores publicados por usuarios se usan 1:1, sin Clear Bass.
Fuentes: captura en la reseña de Kuulokenurkka/Headphone Corner, sony-device-center PR #44 (raw 0..12,
XM6 fw 3.1.5). Datos de Headphone Corner: Q ≈ 0,9 por banda (16k ≈ 0,4); los perfiles custom bajan
~3 dB el volumen; con cable el EQ no se aplica; "Off" **no** es plano (tiene su propia curva).

## Limitaciones de la investigación
- **Reddit inaccesible** al rastreador: ningún upvote verificado (la guía Reddit del XM5 queda "no verificable").
- **Head-Fi** tras anti-bot: solo fragmentos vía buscador.
- **RTINGS** no publica EQ para la app de Sony de estos modelos (mide el XM6 con bajo +5 dB).
- **AutoEq**: solo enlazado; sus datos de medición no tienen licencia de redistribución.
- Presets de fábrica de Sony grabados en un XM4 fw 2.9.0; su igualdad en XM5/XM3 no está verificada.

## Fuentes principales
| Id | Fuente | Autor | Fecha |
|---|---|---|---|
| S1 | https://www.youtube.com/watch?v=3UhCS97tlUA · https://www.loudnwireless.com/blog/make-wh-1000xm6-sound-better-now-best-custom-eq | Loud and Wireless (Aaron) | 2025-05-24 |
| S2 | https://www.youtube.com/watch?v=vXe5OzSrpp8 | Super* Review | 2025-06 |
| S3 | https://kuulokenurkka.com/en/review-sony-wh-1000xm6/ | Headphone Corner (Sword of Damocles) | 2025-07-20 |
| S4 | https://recordingnow.com/blog/sony-wh-1000xm6-review/ | RecordingNOW (Odi Productions) | 2025-06 |
| S5 | https://www.youtube.com/watch?v=Ng0zpx95tA0 | crinacle (valores no extraídos) | 2025-06-29 |
| S6 | https://www.head-fi.org/threads/sony-wh-1000xm5-wireless-noise-cancelling.963073/page-21 | Head-Fi | 2022 |
| S7 | https://kuulokenurkka.com/en/review-sony-wh-1000xm5/ | Headphone Corner | 2022-07-10 |
| S8 | https://www.reddit.com/r/SonyHeadphones/comments/14plfz0/ (vía github.com/RubenKelevra/Sony-WH-1000XM5) | Reddit | ≈2023-07 |
| S9 | https://www.youtube.com/watch?v=Z7kAnU4F7k0 | backchatter | 2022-07-18 |
| S12 | https://www.youtube.com/watch?v=2i7uB_AlDzs | Spencer's Stuff | — |
| S13 | https://www.tomsguide.com/opinion/sonys-wh-1000xm4-headphones-are-great-heres-how-i-made-them-sound-even-better | Tom's Guide | 2022-12-10 |
| S16 | https://github.com/B-Howell/soundconnect-desktop (presets de fábrica XM4 fw 2.9.0, MIT) | B-Howell | — |

## Evidencia de validación (resumen)
| Preset | Modelo | Tipo | Evidencia | Nivel |
|---|---|---|---|---|
| "Singapore Spring" (S1) | XM6 | Subjetivo | Vídeo 64k vistas / 1,7k likes, ~60 comentarios positivos | **Alta** |
| Super* Review, vídeo (S2) | XM6 | Medición + preferencia | 38,5k vistas / 1k likes, 20+ confirmaciones | **Media‑alta** |
| Super* Review "Neutral" (S2) | XM6 | Medición | Comentario fijado del autor, 76 likes | Media |
| Headphone Corner Harman (S3) | XM6 | Medición (Harman 2018, ANC on) | Sin métricas de adopción | Baja (comunidad) |
| RecordingNOW V1/V2.1/V3 (S4) | XM6 | Subjetivo | Pocas confirmaciones | Baja‑media |
| backchatter (S9) | XM5/XM4/XM3 | Subjetivo | 111k vistas; comentario con valores 192 likes; curva repetida en Head-Fi y YouTube | **Alta** |
| Sword of Damocles (S6/S7) | XM5 | Medición (MiniDSP EARS, ANC on) | Perfil más citado del hilo XM5 de Head-Fi | **Media‑alta** |
| "Harman de Reddit" (S12) | XM4 | Medición declarada | ≥4 fuentes independientes | **Alta** |
| Bright + Clear Bass | XM5/XM3 | Subjetivo | Recomendado en Head-Fi y YouTube (61 y 213 likes) | Alta (patrón) |

## Consenso
- **XM6**: el tuning de fábrica tiene exceso de grave/medio‑grave (100–600 Hz); los presets por medición
  bajan 125 Hz, suben 1k (+2…+5) y 4k (+3…+6) y moderan 8k; los subjetivos populares hacen una "V"
  (31/63 +2…+3, 8k +4, 16k +5). 16 kHz no tiene consenso.
- **5 bandas (XM5/XM4/XM3)**: 400 Hz en 0 o negativo, 1k–2.5k +3…+8, 6.3k–16k +3…+10, Clear Bass −4…+8.
  Los medidos priorizan 1k–2.5k y dejan Clear Bass cerca de 0.

## Lista corta (valores para el dispositivo)
**XM6** (31 63 125 250 500 1k 2k 4k 8k 16k; M = medición, S = subjetivo)
- Singapore Spring (S): 2 3 1 −1 1 1 0 0 4 5
- Super* Review vídeo (M): 1 −3 0 0 4 2 2 3 1 5
- Super* Review Neutral (M): 1 −1 −4 1 2 5 −2 5 3 1
- Super* Review Hyper (M): 1 1 −3 −3 4 2 1 6 2 6
- Headphone Corner Harman (M): 2 1 −1 1 1 5 −1 3 3 −2
- RecordingNOW V1 (S): −5 4 −1 −3 0 4 1 −2 −4 1
- RecordingNOW V2.1 (S): −4 3 1 −4 1 3 1 2 −4 −2
- RecordingNOW V3 (S): −3 4 1 −2 1 4 2 −1 −4 1

**XM5** (400 1k 2.5k 6.3k 16k | Clear Bass)
- Sword of Damocles (M): 4 6 4 1 2 | 1
- backchatter (S): 0 3 6 7 5 | −1
- Bright + CB +2 (S): 0 5 7 7 9 | 2
- "Cerca de Bose QC" (S): −2 1 4 7 10 | 4
- Monitor (S): 0 3 6 3 1 | −4
- Guía Reddit (S, no verificable): −3 4 5 1 9 | 8
- RecordingNOW Rock/Metal (S): −3 0 3 −2 2 | 0
- RecordingNOW Pop/Hip-Hop (S): −6 1 5 −4 3 | −2

**XM4**: "Harman de Reddit" (M) 0 4 6 2 0 | −1 · backchatter (S) 0 3 6 7 5 | −1 · Excited+ (S) −1 1 0 3 10 | 10 ·
Menos bajo (S) 2 3 4 4 5 | −3 · Tipo DT 1990 (S) −4 1 −1 −2 1 | −4 · Tom's Guide (S) 1 2 2 3 2 | 1

**XM3**: Bright + CB +7 (S) 0 5 7 7 9 | 7 · backchatter (S) 0 3 6 7 5 | −1 · Harman XM4 (M heredado) 0 4 6 2 0 | −1 ·
Bright + CB 0 (S) 0 5 7 7 9 | 0

## Licencias y atribución
- Valores numéricos publicados por usuarios: en general hechos, pero **acreditar autor y enlace** en el perfil.
- Nombres propios ("Singapore Spring", recetas de Super* Review): pedir permiso o usar nombre descriptivo + crédito.
  Super* Review vende un EQ pack (squig.link): usar solo los valores publicados en abierto.
- AutoEq: código MIT, mediciones de terceros sin licencia → solo enlazar
  (https://github.com/jaakkopasanen/AutoEq/blob/master/results/INDEX.md). Su `FixedBandEQ` usa las bandas del XM6.

## Pendiente
1. Transcribir el EQ de crinacle para el XM6 (vídeo, 07:10) y los de Loud and Wireless para XM5/XM4/XM3.
2. Verificar a mano los posts de Reddit citados.
3. Leer del dispositivo los presets de fábrica de XM5 y XM3.
4. Probar en hardware que las curvas con varias bandas a +5/+6 no saturan.
