# Instalación, permisos y builds reproducibles

## macOS (13" o mayor, macOS 15+, Apple Silicon o Intel)
1. Con widgets (recomendado): `cd macos && Scripts/build_xcode.sh && Scripts/make_dmg.sh`
   (sin widgets: `Scripts/build_app.sh`, solo SwiftPM) → `macos/dist/XM Audio Studio <versión>.dmg`.
2. Abre el `.dmg` y arrastra **XM Audio Studio** a Aplicaciones (o a `~/Applications`). **No la ejecutes
   desde `~/Documents`, `~/Desktop` ni `~/Downloads`**: macOS no arranca ahí la extensión del widget (sandbox).
   Abre la app una vez para que el widget aparezca en la galería de widgets.
3. Primera apertura: concede **Bluetooth** cuando macOS lo pida (Ajustes del Sistema → Privacidad y
   seguridad → Bluetooth). Firmado con tu certificado "Apple Development": macOS conserva el permiso
   entre builds. No está notarizado (requiere Developer ID); en otros Macs habría que abrirlo con
   clic derecho → Abrir.
4. Opcional: Ajustes (⌘,) → "Abrir al iniciar sesión" (`SMAppService`).
5. El EQ avanzado DSP (cuando esté activo) pedirá "Grabación de audio del sistema".

## Android (8.0+, probado en API 35)
- Depuración: `cd android && ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- Versión final: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
  (minificado, firmado con la clave local de `android/keystore.properties`; sin ese archivo usa la de depuración).
- Instalar: `adb install -r <apk>` o copiar el APK al teléfono y permitir "instalar apps desconocidas".
- Permiso **Dispositivos cercanos** (Android 12+). La app no busca dispositivos ni toca emparejamientos.

### Clave de firma de versión (local)
`~/.xmaudiostudio/release.jks`, generada el 2026-10-21. **Haz copia de seguridad**: sin ella no se
pueden publicar actualizaciones que Android acepte sobre la instalación existente. La contraseña está
en `android/keystore.properties` (ignorado por git, permisos 600).

## Builds reproducibles
| Herramienta | Versión fijada |
|---|---|
| Swift / Xcode | Swift 6.3.3 / Xcode 26.6 (tools-version 6.0, modo de lenguaje 5) |
| Gradle | 8.14.3 (wrapper con checksum en `gradle-wrapper.properties`) |
| AGP / Kotlin / Compose BOM | 8.13.2 / 2.2.21 / 2025.12.01 (`gradle/libs.versions.toml`) |
| JDK | 17 (toolchain de Gradle) |
| Fixtures | `tools/fixtures/*.py` (stdlib); CI comprueba que regenerarlos no produce cambios |

macOS no tiene dependencias externas; Android solo AndroidX/Kotlin (sin servicios de Google).
