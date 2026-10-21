# AutoEQ interno: ajuste a bandas fijas

Decisión y política de datos: [ADR-0006](../decisions/ADR-0006-autoeq-fitting-and-data.md).
Código: `GraphicEqFitter` (Kotlin/Swift, línea a línea), `AutoEqImporter`.

1. Objetivo T(f): respuesta del `ParametricEQ.txt` (sin preamp) o interpolación log del `GraphicEQ.txt`.
2. Rejilla: 240 puntos log entre 20 Hz y 20 kHz.
3. Pesos: 0,5 (<40 Hz) · 1,0 (40 Hz–6 kHz) · rampa log hasta 0,3 (10 kHz) · 0,3 por encima.
4. Base linealizada: respuesta en dB de cada banda a +1 dB.
5. Mínimos cuadrados con cotas [mín, máx] y desplazamiento de nivel libre o (no se envía):
   min Σ w_i (Σ_k x_k B_k(f_i) + o − T_i)² + λ Σ x_k², λ = 0,02, por descenso coordinado cíclico
   (hasta 500 barridos, tolerancia 1e‑10).
6. Cuantización: pasos = clamp(floor(x/paso + 0,5)); luego búsqueda entera ±1 sobre la respuesta
   **exacta** (no lineal) hasta que ningún cambio mejore el objetivo.
7. Se informa el error RMS ponderado y el máximo |error| entre 20 Hz y 10 kHz.

Limitaciones: forma de filtro y dB/paso del XM6 no verificados; ±6 pasos limitan correcciones
grandes; la medición de AutoEq corresponde a una unidad y condiciones concretas (ANC activado).
