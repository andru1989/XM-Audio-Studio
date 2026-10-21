# Widgets de WidgetKit (macOS)

Widgets de escritorio y del Centro de notificaciones para XM Audio Studio (Etapa 5). La idea
viene de yelloduxx/xm6-control (`8b98102`, `0d71a47`, ver
[`docs/research/forks-analysis.md`](../research/forks-analysis.md) §4.2), pero la IPC es otra:
App Group en lugar de ficheros en Application Support, y los comandos no se pierden si el canal
de control está liberado.

| Tamaño | Contenido | Interacción |
|---|---|---|
| Pequeño | Foto del modelo, batería, modo de ruido, estado (en vivo / último estado) | Clic: abre la app |
| Mediano | Cabecera (foto, nombre, estado, batería) + 3 botones: Cancelación / Ambiente / Desactivado | `SetNoiseModeIntent` |
| Grande | Cabecera + control de ruido + 6 presets de EQ (Off, Heavy, Clear, Hard, Soft, Custom) | `SetNoiseModeIntent`, `ApplyEqPresetIntent` |

Estilo plano de la app: colores de `Surface` (Views/Theme.swift) y `StudioColor`
(Studio/ResponseChart.swift), selección con el color de acento del sistema. Textos en inglés y
español (`macos/WidgetExtension/*.lproj`).

## Compilar

```bash
cd macos
Scripts/build_xcode.sh            # Release universal (arm64 + x86_64) → build/XM Audio Studio.app
```

- Requiere Xcode completo y XcodeGen (`brew install xcodegen`). El script ejecuta
  `xcodegen generate` (el `.xcodeproj` está en `.gitignore`) y `xcodebuild`.
- `project.yml` compila las mismas fuentes de `Sources/XMAudioStudio` y enlaza los productos de
  biblioteca del paquete local (`SonyHeadphonesKit`, `AudioStudioKit`, `WidgetBridgeKit`). Añade
  el target `XMAudioStudioWidget` (app-extension, WidgetKit + App Intents, macOS 15, sandbox).
- `swift build`, `swift test` y `Scripts/build_app.sh` siguen igual. Ese `.app` no lleva widget
  ni el entitlement del App Group, así que el puente de widgets queda inactivo (Ajustes lo dice).
- **Instalar fuera de `~/Documents`, `~/Desktop` y `~/Downloads`** (p. ej. en `/Applications`)
  antes de añadir el widget. Un ejecutable con sandbox guardado en una carpeta protegida por TCC
  no arranca: `secinitd` no puede leerlo (`deny file-read-data … unable to realpath main
  executable`, `SIGTRAP` en `_libsecinit_appsandbox`). Lo comprobé con un binario firmado con los
  entitlements de la extensión; la extensión sigue la misma regla. La app en sí (sin sandbox)
  funciona desde cualquier carpeta.

Variables: `XMAS_ARCHS`, `XMAS_TEAM_ID` (por defecto `P5M226DH69`), `XMAS_SIGNING`
(`auto` | `automatic` | `manual`), `XMAS_SIGN_IDENTITY`.

## Firma y App Group

ID del grupo: **`P5M226DH69.group.io.github.andru1989.xmaudiostudio`** (estilo macOS, con el
prefijo del Team ID). Está en `Config/XMAudioStudio.entitlements`,
`Config/XMAudioStudioWidget.entitlements` y `Sources/WidgetBridgeKit/AppGroup.swift`.

| Firma | Resultado (Xcode 26.6, macOS 26.5, 2026-10-21) |
|---|---|
| Automática: `DEVELOPMENT_TEAM=P5M226DH69`, `-allowProvisioningUpdates` | **Funciona.** No hay cuenta en Xcode ni perfiles instalados. Xcode firma app y extensión con "Apple Development" **sin perfil de aprovisionamiento**, porque ni el App Group con prefijo de equipo ni el sandbox lo necesitan. Es la ruta por defecto. |
| Manual (respaldo): build con `CODE_SIGNING_ALLOWED=NO`, luego `codesign` de dentro hacia fuera (appex y después app) con `--options runtime` y los entitlements | **Funciona** (`XMAS_SIGNING=manual`). Mismo resultado: TeamIdentifier `P5M226DH69` y entitlements correctos. |
| Ad hoc (sin identidad) | El bundle se crea, pero sin Team ID el grupo no está autorizado: el script quita el entitlement del grupo y el widget no tendrá datos. |

Sobre el formato del ID:

- **Estilo macOS (`TEAMID.…`):** lo autoriza el Team ID de la firma, sin perfil. Lo probé con
  binarios firmados con "Apple Development", uno sin sandbox y otro con sandbox: los dos
  leen y escriben en el contenedor y no aparece ningún aviso de "acceder a datos de otras apps".
- **Estilo iOS (`group.…`):** según la documentación de Apple, en macOS 15+ necesita un perfil que
  lo autorice. Un binario de línea de comandos con sandbox sí pudo usarlo sin perfil en esta
  máquina, pero no lo probé con una app lanzada por LaunchServices, que es donde macOS 15+ aplica
  la protección de contenedores. Para eso hacen falta un perfil y una cuenta, que aquí no hay.
  Por eso se usa el estilo macOS.
- La app no tiene sandbox: necesita IOBluetooth/RFCOMM. Solo declara el App Group. La extensión
  tiene sandbox, como exige WidgetKit.

## IPC

Contenedor `~/Library/Group Containers/P5M226DH69.group.io.github.andru1989.xmaudiostudio/Widget/`:

| Fichero | Escribe | Lee | Contenido |
|---|---|---|---|
| `snapshot.json` | app | widget | `WidgetSnapshot`: modelo, nombre, batería, carga, modo de ruido (+ nivel), modo de escucha, código de preset EQ, nombre del perfil, `link` (`live`, `connecting`, `released`, `offline`, `appClosed`), `isDemo`, `observedAt`, `updatedAt`, `pendingActions`, `lastOutcome` |
| `Commands/<ms>-<uuid>.json` | widget | app (lo borra) | `WidgetCommand`: id, `issuedAt`, acción (`setNoiseMode`, `applyEqPreset`) |

Un solo escritor por fichero y escritura atómica (temporal + rename), sin bloqueos.

```
clic en botón ─▶ AppIntent.perform() (proceso de la extensión, sandbox)
                 1. Commands/…json  +  notify "P5M226DH69.group.io.github.andru1989.xmaudiostudio.command"
                 2. ¿app en marcha? (NSRunningApplication) ─ no ─▶ NSWorkspace.openApplication(app contenedora)
                 3. WidgetCenter.reloadAllTimelines()  (el botón aparece "Aplicando…")
app: WidgetBridge (Darwin observer / arranque / didBecomeActive) ─▶ toma los comandos
     ├─ > 60 s de antigüedad ─▶ "expired", no se aplican
     ├─ el último del mismo tipo sustituye a los anteriores
     ├─ canal cerrado ─▶ controller.refreshState() (modo bajo demanda) o autoConnect() (modo clásico)
     ├─ espera a .connected Y al estado leído en esta conexión (ambientSound / equalizer;
     │   modo de escucha hasta 8 s) ─▶ se escribe sobre ese estado, nunca sobre valores por defecto
     │   ni de una sesión anterior (regla 3 de AGENTS.md)
     ├─ fallo, otro dispositivo, sin estado, BGM/Cine bloquea el EQ, 30 s sin respuesta ─▶ lastOutcome
     └─ snapshot.json (debounce 150 ms, solo si cambia el contenido) + reloadAllTimelines (≤ 1/s)
```

- **No se pierden comandos con el canal liberado.** `HeadphonesController` tampoco cambia: el
  puente usa las mismas vías que la interfaz. Tras el comando, el canal se libera otra vez a los 20 s.
- **Último estado conocido.** Al liberar el canal el controlador borra su estado. El snapshot
  conserva los últimos valores con `link = released` y `observedAt`, y el widget muestra
  "Último estado · hace N min". Si la app muere sin escribir `appClosed`, el widget lo detecta con
  `NSRunningApplication` y tampoco la muestra "en vivo". Los valores del modo demo no pasan al modo
  real ni al revés.
- **App Nap.** Si la app está en segundo plano y sin ventanas, macOS agrupa sus temporizadores en
  intervalos de segundos. Con el transporte demo un comando enviado tras la liberación se quedó en
  "conectando" hasta agotar el tiempo. Mientras atiende un comando, el puente mantiene
  `ProcessInfo.beginActivity(.userInitiated)` y la suelta 3 s después.
- **Seguridad.** La notificación Darwin y el buzón no están autenticados: cualquier proceso del
  mismo usuario con acceso al contenedor puede encolar un comando. Solo se aceptan dos acciones
  inocuas, JSON de ≤ 4 KB y como mucho 32 en cola.

## Verificación

- `swift test`: `WidgetBridgeKitTests` (formato, buzón, caducidad, notificación Darwin) y
  `XMAudioStudioTests` (puente + controlador con el simulador XM6). Los tests cubren: comando con el
  canal liberado, espera al estado leído (el nivel ambiente del simulador, 12, se conserva), EQ,
  sustitución, reconexión tras liberar, comando dejado con la app cerrada, sin audífonos
  emparejados, caducidad, modo clásico y throttling.
- `Scripts/verify_widget_ipc.sh show|noise <modo>|eq <preset>`: compila una sonda con
  `WidgetBridgeKit`, la firma con **los entitlements de la extensión** (sandbox + grupo) y hace lo
  mismo que un botón contra la app en marcha. Con `XMAS_LAUNCH_APP=…` y la app cerrada, la lanza
  desde el sandbox, como el intent. Un lanzador con sandbox no puede pasar argumentos: la app
  arranca en el modo de sus ajustes.
- Registro: `pluginkit -m -v -p com.apple.widgetkit-extension | grep xmaudiostudio`.

Resultados (2026-10-21, Xcode 26.6, macOS 26.5, modo demo, sin audífonos reales):

| Comprobación | Resultado |
|---|---|
| `Scripts/build_xcode.sh` | BUILD SUCCEEDED con firma automática. App y appex universales (`x86_64 arm64`), `codesign --verify --deep --strict` OK, TeamIdentifier `P5M226DH69`, grupo en los dos, sandbox solo en la appex. `Metadata.appintents` contiene `SetNoiseModeIntent(mode)`, `ApplyEqPresetIntent(preset)`, `NoiseModeOption` y `EqPresetOption`. |
| `pluginkit` | `io.github.andru1989.xmaudiostudio.widget(0.1.0)` registrado: la copia de DerivedData y la copia lanzada desde una carpeta no protegida. |
| Comando con el canal sin abrir | Conecta, lee el estado y aplica. Publicado en 0,6–0,7 s. |
| EQ Heavy / Soft | Aplicado en 0,1–0,2 s. El snapshot incluye el perfil ("Sony Heavy"). |
| Liberación a los 20 s | `link = released`, valores conservados. |
| Comando tras más de 60 s liberado y en segundo plano | Aplicado en 0,7 s. Antes de `beginActivity`, el mismo caso terminaba en `timedOut` y después en `unreachable`. |
| App cerrada + `XMAS_LAUNCH_APP` | La sonda (con sandbox) lanzó la app, que tomó el comando 0,5 s después de arrancar. Arrancó en modo real porque no se le pueden pasar argumentos, no encontró los audífonos y lo indicó con `unreachable` a los 16,7 s, sin escribir nada. |

## Limitaciones

- No verificado: que el widget aparezca en la galería, cómo se ve, y un clic real en
  `Button(intent:)` (WidgetKit ejecutando `perform()` y decodificando el parámetro `AppEnum`).
  yelloduxx informó parámetros `nil` con firma ad hoc (`LNActionExecutorErrorDomain 2010`); aquí
  los enums y los intents están en el módulo de la extensión y `Metadata.appintents` los incluye
  con sus parámetros. Aun así, hay que probarlo a mano.
- Sin hardware: toda la verificación de extremo a extremo se hizo en modo demo.
- La comprobación "¿está la app en marcha?" se hace por bundle ID. Si está abierta una compilación
  SwiftPM (sin entitlement), el widget no lanza otra y el comando caduca a los 60 s.
- WidgetKit limita las recargas. El puente solo recarga cuando cambia algo visible, como mucho una
  vez por segundo. Con el canal liberado no llegan cambios de batería.
- Los presets son los de firmware XM6. El nombre del perfil (`eqProfileName`) se muestra, pero los
  perfiles de la biblioteca se aplican desde la app.
- Las fotos de `Resources/Models` (© Sony, uso personal) también van en el bundle de la extensión.
  Rige la regla 7b de AGENTS.md.
- xcodebuild registra en LaunchServices la copia de `build/DerivedData`. Si hay varias copias,
  `pluginkit` elige una.

## Ficheros

`macos/project.yml`, `macos/Scripts/build_xcode.sh`, `macos/Scripts/verify_widget_ipc.sh`,
`macos/Config/*.entitlements`, `macos/WidgetExtension/` (extensión),
`macos/Sources/WidgetBridgeKit/` (IPC compartida), `macos/Sources/XMAudioStudio/WidgetBridge.swift`
(lado app), `macos/Tests/WidgetBridgeKitTests/`, `macos/Tests/XMAudioStudioTests/`.
