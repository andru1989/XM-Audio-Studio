# ADR-0005 — `HeadphoneDevice`, adaptadores por generación y catálogo de capacidades

Fecha: 2026-10-21 · Estado: Aceptada (actualizada 2026-10-21: adaptadores XM5 y v1 en Android)

## Decisión
- Interfaz independiente del modelo: `HeadphoneDevice` (Kotlin: `core/device/HeadphoneDevice.kt`).
  Expone `snapshot` (fase de conexión + estado + capacidades), `execute(DeviceCommand)`, `refresh`,
  `connect`, `disconnect`, `setSurfaceVisible`.
- La sesión (transporte, ACK, ciclo de vida) es común. Lo específico de cada generación vive en un
  `ProtocolAdapter` (consultas iniciales, codificación de intenciones, decodificación). Existen (Android)
  `Xm6Adapter`, `Xm5Adapter` (MDR v2) y `MdrV1Adapter` (XM2–XM4), todos sin prueba en hardware salvo
  la lectura del XM6. macOS: solo XM6 conectado; códecs v1/XM5 con tests de conformidad. Detalle:
  `docs/compatibility/OTHER-MODELS.md`.
- `DeviceCatalog` = matriz de capacidades por modelo, con nivel de evidencia por función. Fuente
  de verdad compartida: `shared/protocol-spec/models.json` (ambas plataformas lo verifican en tests).
- Detección: nombre Bluetooth (pista) → protocolo del init (confirmación) → `04 01` nombre de modelo y
  `04 02` firmware (lecturas seguras e iguales en v1/v2).
- El ecualizador se describe por modelo (`EqualizerSpec`: bandas, frecuencias, pasos, offset, Clear
  Bass). Nada asume 10 bandas.
- Perfiles AutoEQ llevan `model`; un perfil de XM6 no se aplica a otro modelo.

## Seguridad
Modelos sin adaptador **nunca reciben bytes** (ni siquiera el init). Si el init responde con otra
familia de protocolo que la esperada, la sesión aborta antes de cualquier escritura.

## Consecuencias
Añadir un modelo = adaptador + entrada en el catálogo + fixtures de captura real + prueba en hardware.
