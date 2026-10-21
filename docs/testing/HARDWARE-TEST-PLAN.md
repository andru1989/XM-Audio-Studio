# Procedimiento de prueba con tus WH-1000XM6

Objetivo: subir funciones del nivel SIM/PEND a **HW** en `docs/compatibility/FEATURE-STATUS.md`.
Nada de este procedimiento escribe firmware, restablece los audífonos ni toca emparejamientos.

## Preparación
1. Actualiza los audífonos con Sony Sound Connect y anota la versión de firmware.
2. Empareja los XM6 con el Mac (Ajustes del Sistema → Bluetooth) y con el teléfono (Ajustes de Android).
3. **Cierra Sound Connect** en el teléfono (solo admite una sesión de control).
4. macOS: `cd macos && Scripts/build_app.sh` y abre `.build/XM Audio Studio.app`.
   Ajustes (⌘,) → Desarrollo → activa "Registro de depuración" (guarda tramas en
   `~/Library/Application Support/XM Audio Studio/protocol.log`).
5. Android: instala `android/app/build/outputs/apk/debug/app-debug.apk`, concede "Dispositivos cercanos",
   Ajustes → activa "Registrar tráfico del protocolo".

## Pruebas (marca ✅/❌ y guarda el log)

| # | Prueba | Esperado |
|---|---|---|
| 1 | Abrir la app con los XM6 conectados por audio | Conecta, muestra modelo y firmware leídos (`05 01`/`05 02`) |
| 2 | Comparar ANC/ambiente/EQ con lo que muestra Sound Connect | Coinciden (sin escribir nada al conectar) |
| 3 | Pulsar el botón NC/AMB físico | La app refleja el cambio en < 1 s |
| 4 | Cambiar NC → Ambiente → nivel 5 → enfocar voz | Los audífonos cambian; el log muestra `68 17 …` y `69 19 …` |
| 5 | Aplicar "Rock" y luego "Original Sony" | Se oye el cambio; Sound Connect muestra la misma curva/preset |
| 6 | Arrastrar varias bandas rápido | Sin retrasos acumulados; la curva final queda aplicada |
| 7 | Importar un `ParametricEQ.txt` de AutoEq para el XM6 y "Guardar y aplicar" | Perfil "Medido" aplicado |
| 8 | Cerrar ventanas y esperar 25 s; abrir Sound Connect en el teléfono | Sound Connect conecta sin errores (la app liberó el canal) |
| 9 | Con Sound Connect abierto, tocar un control en el Mac | Error explicado, sin reintentos agresivos |
| 10 | Cerrar Sound Connect, tocar un control en el Mac | Recupera el control y **lee el estado antes de escribir** |
| 11 | Mac ↔ Android: cambiar el EQ en uno, abrir el otro | El segundo muestra el estado real, no uno obsoleto |
| 12 | Cambiar fuente de reproducción (multipunto) | La reproducción pasa al otro dispositivo |
| 13 | Modo BGM → mirar el EQ | La app avisa que el firmware desactiva el EQ |
| 14 | Apagar los audífonos con la app abierta | Estado "cedido/desconectado" sin bucle de reconexión |

## Verificaciones de protocolo pendientes (solo lecturas)
- Respuesta a `50 04 01` (bandas y pasos del EQ): ¿10 bandas / 13 pasos?
- Tipo de batería real (`23 00` o `23 08`).
- Significado de los bytes [7] y [8] de `67 19` (auto‑ambiente, sensibilidad).
- dB por paso del EQ: comparar con una medición (fuera de alcance del software).

En macOS también existe la herramienta de sondeo: `open "XM Audio Studio.app" --args --probe "50 04 01" "5a 04"`
(solo para comandos de **lectura**; registra en `probe.log`).

## Al terminar
Envía/guarda los logs y actualiza `FEATURE-STATUS.md` (nivel **HW** con fecha y firmware).
