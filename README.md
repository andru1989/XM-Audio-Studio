# XM Audio Studio

**Controla y ecualiza tus audífonos Sony de la serie 1000X (MDR-1000X, WH-1000XM2, XM3, XM4, XM5 y
XM6) desde tu Mac y tu teléfono Android, sin la app oficial, sin cuentas y sin internet.**

XM Audio Studio es una pareja de aplicaciones nativas (macOS y Android) que se comunican directamente
por Bluetooth con los audífonos Sony de la serie 1000X para:

- **Controlarlos**: cancelación de ruido, sonido ambiente y su nivel, enfocar en la voz, modo de escucha
  (Estándar, Música de fondo, Cine), Hablar para chatear, pausa al quitárselos, apagado automático,
  batería y cambio del dispositivo que reproduce (multipunto).
- **Ecualizarlos mejor que la app oficial**: el ecualizador de 10 bandas de los propios audífonos con
  gráfica de respuesta, perfiles ilimitados, deshacer, comparación A/B, importar/exportar, y
  **AutoEQ**: convierte las correcciones de AutoEq (mediciones reales) en la mejor aproximación posible
  para las bandas de los audífonos.
- **Guardar tu configuración completa** ("Escenas"): EQ + control de ruido + modo de escucha y demás
  ajustes en un solo perfil, que aplicas en un clic en el Mac o en el teléfono. También puede traer a la
  app la curva que ya tienes configurada en Sony Sound Connect.
- **Convivir con tus otros dispositivos**: los audífonos solo admiten un control a la vez, así que la app
  toma el control cuando la usas y lo libera a los 20 segundos para que Sound Connect, tu teléfono o tu
  Mac puedan usarlo. Siempre lee el estado real antes de cambiar nada.
- **EQ avanzado en el Mac (beta)**: ecualizador paramétrico aplicado al audio del sistema, con
  protección contra recortes, A/B y aviso para no duplicar la corrección del EQ de los audífonos.

> Proyecto independiente, **no afiliado ni respaldado por Sony**. "Sony", "WH-1000XM6" y "Sound
> Connect" se usan solo para identificar el hardware compatible. Sony Sound Connect sigue siendo la app
> oficial para actualizar el firmware y para funciones que esta app no implementa.

## Para quién es

Para cualquiera que tenga unos audífonos de diadema de la serie Sony 1000X —MDR-1000X, WH-1000XM2,
WH-1000XM3, WH-1000XM4, WH-1000XM5 o WH-1000XM6— y quiera controlarlos y personalizar su sonido desde
el Mac (barra de menús, ventana o widgets) y desde Android (app, Ajustes rápidos o widget), con más
control del ecualizador que la app oficial.

El soporte avanza por modelo: el WH-1000XM6 es el más completo hoy, y los demás modelos se van
habilitando y validando con hardware real (ver la tabla de compatibilidad).

## Compatibilidad

| | macOS | Android |
|---|---|---|
| Sistema | macOS 15 o superior, Apple Silicon e Intel (binario universal) | Android 8.0 o superior |
| WH-1000XM6 | Completo (lectura verificada con hardware real; escrituras pendientes de prueba) | Completo (probado con simulación) |
| WH-1000XM5 / XM4 / XM3 / XM2 | Detectados, aún no controlados | Experimental, sin prueba en hardware |
| MDR-1000X | No soportado | No soportado |

Estado detallado por función: [`docs/compatibility/FEATURE-STATUS.md`](docs/compatibility/FEATURE-STATUS.md).

## Instalación rápida

- **Mac**: genera el instalador con `cd macos && Scripts/build_xcode.sh && Scripts/make_dmg.sh`, abre
  `macos/dist/XM Audio Studio <versión>.dmg` y arrastra la app a **Aplicaciones**. Concede el permiso de
  Bluetooth la primera vez.
- **Android**: `cd android && ./gradlew :app:assembleRelease` e instala
  `android/app/build/outputs/apk/release/app-release.apk`. Concede el permiso "Dispositivos cercanos".

Los audífonos deben estar emparejados con el equipo desde los ajustes del sistema; la app nunca busca
dispositivos ni modifica emparejamientos. Más detalle: [`docs/distribution/INSTALL.md`](docs/distribution/INSTALL.md).
Para probar sin audífonos, activa el **modo demostración** (simula unos XM6).

## Privacidad

Todo funciona en local: sin cuentas, sin servidores, sin analítica. Los perfiles se guardan en el
propio equipo y se comparten exportando un archivo JSON.

## Cómo está hecho

Monorepo con dos apps nativas que **no comparten código, sino especificación y pruebas**:

| Ruta | Contenido |
|---|---|
| [`macos/`](macos/) | App SwiftUI (barra de menús, ventana, estudio de EQ, widgets, EQ DSP). Basada en [XM6 Control](https://github.com/ruimartins23/xm6-control) (MIT) y la gestión de canal de Anneo22 (Apache-2.0). |
| [`android/`](android/) | App Kotlin/Jetpack Compose. `core/` (JVM puro: protocolo, sesión, EQ, perfiles) y `app/` (Bluetooth, UI, Ajustes rápidos, widget). |
| [`shared/`](shared/) | Especificación del protocolo Sony MDR, fixtures de bytes, catálogo de modelos, formato de perfiles, vectores de AutoEQ y tokens de diseño. Ambas apps se prueban contra estos mismos archivos. |
| [`tools/`](tools/) | Generadores de fixtures y la prueba de escrituras con hardware. |
| [`docs/`](docs/) | Arquitectura, decisiones (ADR), Bluetooth, DSP, compatibilidad, pruebas, backlog. |

### Compilar y probar

```bash
cd macos && swift test                      # tests de protocolo, sesión, conformidad, EQ, perfiles, DSP
cd macos && Scripts/build_xcode.sh          # app con widgets (Xcode + XcodeGen)
cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
python3 tools/hardware/xm6_write_test.py    # prueba de escrituras con unos XM6 reales (restaura tus ajustes)
```

Requisitos: Xcode 26 / Swift 6.3, XcodeGen, JDK 17 y Android SDK 36.

## Contribuir o continuar el desarrollo

Empieza por [`AGENTS.md`](AGENTS.md) (reglas y mapa del repositorio), luego
[`docs/BACKLOG.md`](docs/BACKLOG.md), [`docs/KNOWN-ISSUES.md`](docs/KNOWN-ISSUES.md) y el
[`CHANGELOG.md`](CHANGELOG.md).

## Licencias

Código propio bajo MIT ([`LICENSE`](LICENSE)); componentes de terceros en
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Las fotos de producto de `shared/model-images/` son
© Sony Corporation y no están cubiertas por la licencia del proyecto (ver [`shared/model-images/SOURCES.md`](shared/model-images/SOURCES.md)).
No se incluyen datos de medición de AutoEq: cada usuario importa los suyos.
