# Escenas: perfiles con la configuración completa

Estado: implementado en macOS y Android, cubierto por pruebas con fixtures compartidos y con el
XM6 simulado. **No probado en hardware real.**

## Qué es

Un perfil puede llevar, además de su EQ, una **escena**: el resto de ajustes de los audífonos.
"Guardar configuración completa" captura lo que los audífonos informan en ese momento y lo guarda
como un perfil de usuario; al aplicarlo más tarde (en Mac o Android) se restauran escena y EQ.
En la lista de perfiles se marca con la etiqueta **Escena**.

## Formato (versión 2)

`shared/preset-schema/profiles.schema.json`, `version: 1 | 2`. Las apps escriben siempre la 2 y
leen ambas. Un perfil puede tener un objeto opcional `scene` (ejemplo:
`examples/export-v2.json`):

| Campo | Valores | Característica requerida (`models.json`) |
|---|---|---|
| `noiseControl.mode` | `noiseCancelling`, `ambientSound`, `off` | `noiseCancelling` (+ `ambientSound` para ese modo) |
| `noiseControl.level` | 1–20 (y `ambientLevelRange`) | `ambientLevel` |
| `noiseControl.focusOnVoice` | bool | `focusOnVoice` |
| `listeningMode` | `standard`, `backgroundMusic`, `cinema` | `listeningMode` |
| `bgmRoomSize` | `small`, `middle`, `large` | `listeningMode` |
| `speakToChat` | bool | `speakToChat` |
| `pauseWhenTakenOff` | bool | `wearDetection` |
| `autoPowerOff` | `whenTakenOff`, `off` | `autoPowerOff` |

Reglas (iguales en `Scenes.kt` y `Scenes.swift`):

- Todo es opcional; solo se guarda lo que los audífonos informaron. `noiseControl` exige `mode`.
- Una escena vacía `{}` se rechaza, y también una escena dentro de un archivo de versión 1.
- Un campo que el modelo del perfil no admite (nivel de soporte no usable) invalida el perfil:
  por ejemplo `listeningMode` en un WH-1000XM5. `deviceEq` sigue siendo obligatorio.
- Los 14 perfiles incluidos (`builtin-profiles.json`, versión 1) no tienen escena.

## Captura

`ProfileLibrary.saveScene(name, modelId, state)` en ambas plataformas:

- **EQ**: curva personalizada (`0xA0`) → `steps` (+ Clear Bass en XM3–XM5); preset Sony conocido
  → `presetCode`; ajuste de usuario de Sound Connect (código desconocido) → las bandas que
  informa, con una nota. Si el EQ no se ha leído, se rechaza.
- **Escena**: `HeadphoneScenes.capture` toma lo informado y lo que el modelo admite. Un nivel de
  ambiente fuera del rango (MDR v1 informa 0 en cancelación) no se guarda.
- La UI solo ofrece guardar con el estado leído en vivo en esta conexión.

## Aplicación

`HeadphoneScenes.commands` (Kotlin, `DeviceCommand`) / `intents` (Swift, `SceneIntent`) dan la
lista ordenada; la sesión codifica cada intención después de leer el estado real (ADR-0003):

1. Control de ruido. Lo que la escena no fija (nivel, voz) se completa con lo que informan los
   audífonos, o nivel 15 si no hay nada.
2. Sala de Música de fondo, **antes** del modo de escucha: el byte de sala viaja con el
   indicador BGM, así la escritura del modo lleva la sala nueva.
3. Modo de escucha y EQ: el firmware desactiva el EQ en Música de fondo o Cine, así que el EQ se
   escribe con el modo en Estándar (después de pasar a Estándar, antes de salir de él).
4. Hablar para chatear, pausa al quitárselos, apagado automático.

Android envía la lista a `HeadphoneSession.execute` (al recibir el ACK, el estado confirmado ya
incluye la sala). macOS llama a los setters de `HeadphonesController` (extensión en
`AudioStudioKit/SceneController.swift`); dos parámetros nuevos y compatibles evitan que la
lectura de estado de una reconexión se cuele entre escrituras: `setListeningMode(_:roomSize:)`
envía sala y modo en un mismo mensaje y `setEqualizerBands(_:coalescing: false)` encola la curva
sin el retardo de 120 ms. No se ha añadido ni cambiado ningún byte del protocolo.

Antes de aplicar, la UI muestra una confirmación con cada ajuste que cambia
(`HeadphoneScenes.changes`) más el EQ. El nivel y la voz solo se listan si el resultado es
Sonido ambiente, y la sala solo si el resultado es Música de fondo.

## Pruebas

- Fixtures compartidos: `examples/export-v1.json`, `examples/export-v2.json`,
  `examples/invalid/scene-*.json`, y `scene-vectors.json` (captura → perfil, escena + estado →
  intenciones en orden y cambios listados).
- Android: `core/.../presets/ScenesTest.kt` (incluye aplicar una escena al XM6 simulado con el
  canal retenido y liberado). macOS: `Tests/AudioStudioKitTests/SceneTests.swift` (incluye el
  mismo recorrido con `HeadphonesController.demo()`).

## Límites conocidos

- Nada de esto se ha probado con audífonos reales; los niveles de soporte de cada ajuste siguen
  siendo los de `docs/compatibility/FEATURE-STATUS.md`.
- No se guardan la sensibilidad ni el tiempo de Hablar para chatear, la fuente de reproducción
  multipunto ni el DSP local de macOS.
- macOS solo controla el XM6: el Clear Bass de un perfil de 5 bandas no se envía desde el Mac.
- Si los audífonos ya están en Música de fondo o Cine y la escena mantiene ese modo, el EQ se
  escribe con el EQ desactivado por el firmware; la confirmación lo avisa.
- Si una escritura falla (sin ACK), las demás se envían igualmente; no hay deshacer de la escena,
  solo del EQ.
