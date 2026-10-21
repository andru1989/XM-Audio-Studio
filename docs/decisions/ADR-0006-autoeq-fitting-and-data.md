# ADR-0006 — AutoEQ interno y datos de medición

Fecha: 2026-10-21 · Estado: Aceptada

## Contexto
Los 10 controles del EQ interno del XM6 no son 10 filtros paramétricos: son bandas fijas
(frecuencias de etiqueta 31 Hz … 16 kHz, **no verificadas**), pasos enteros −6…+6 (verificado), forma
de filtro desconocida.

## Decisión
- Modelo predictivo: cada banda = filtro peaking RBJ, Q = 1,41 (octava), 1 dB/paso (supuesto).
  Todo lo calculado con este modelo se etiqueta como **predicción**, no medición.
- Ajuste: rejilla log de 240 puntos (20 Hz–20 kHz); peso 0,5 <40 Hz, 1,0 hasta 6 kHz, rampa a 0,3
  en 10 kHz; mínimos cuadrados con cotas por descenso coordinado y desplazamiento de nivel libre (no
  se envía); redondeo y búsqueda entera ±1 sobre la respuesta exacta. Determinista y sin dependencias.
- Se muestra objetivo vs aproximación y el error RMS ponderado; nunca se promete equivalencia.
- **Datos AutoEq**: el software es MIT pero los datos de `measurements/`/`results/` no tienen
  licencia. No se incluyen en el repo ni en las apps. El usuario importa `ParametricEQ.txt` /
  `GraphicEQ.txt` (archivo o pegado); la descarga opcional desde GitHub, si se añade, será a petición
  explícita y con atribución de la fuente de medición.
- Perfiles científicos (importados de medición) y subjetivos (diseñados) llevan categoría distinta.

## Verificación pendiente en hardware
Consultar `50 04 01` (bandas/pasos) y `5A 04` (frecuencias) en un XM6 real; medir dB por paso.
