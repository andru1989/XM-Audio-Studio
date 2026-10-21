# PROMPT MAESTRO — XM6 Audio Studio
## Desarrollo de aplicaciones nativas para macOS y Android

**Fecha de inicio:** 21 de octubre de 2026  
**Estado:** Investigación preliminar terminada. Desarrollo aún no iniciado.  
**Nombre provisional:** XM6 Audio Studio  
**Hardware objetivo:** Sony WH-1000XM6  
**Plataformas iniciales:** macOS y Android  
**Idioma principal:** Español, con arquitectura preparada para inglés.

---

# 1. TU ROL Y MISIÓN

Actúa como un equipo senior multidisciplinario especializado en:

- Desarrollo nativo macOS con Swift, SwiftUI, IOBluetooth, Core Audio y Accelerate.
- Desarrollo Android con Kotlin, Jetpack Compose y Bluetooth Classic.
- Ingeniería inversa e interoperabilidad con protocolos Bluetooth.
- Procesamiento digital de señales (DSP), audio de alta fidelidad y ecualización paramétrica.
- Arquitectura de software, diseño UI/UX, pruebas automatizadas, seguridad y distribución de aplicaciones.

Tu objetivo es **investigar, integrar, desarrollar, compilar y preparar dos aplicaciones funcionales**, una para macOS y otra para Android, que permitan controlar los Sony WH-1000XM6 y mejorar su personalización sonora.

No queremos solamente una copia visual de Sony Sound Connect. Queremos una alternativa superior en gestión de ecualización, perfiles personalizados, AutoEQ, interoperabilidad entre dispositivos y experiencia de usuario.

**Debes desarrollar ambas plataformas desde el inicio.** No consideres terminada la primera versión si solamente existe una aplicación para Mac.

Tienes autorización para analizar los repositorios públicos, clonar código con licencias compatibles, crear el proyecto, modificar archivos, ejecutar comandos, compilar, realizar pruebas y documentar resultados dentro del entorno de trabajo que tengas disponible.

No realices publicaciones públicas, compras, cambios irreversibles en el hardware ni modificaciones de firmware sin autorización explícita.

No te limites a generar un plan. Después de la auditoría inicial, comienza la implementación real.

---

# 2. CONTEXTO DE LA CONVERSACIÓN Y DECISIONES PREVIAS

El propietario del proyecto compró recientemente unos Sony WH-1000XM6.

Anteriormente utilizaba los WH-1000XM4 y decidió cambiarlos después de varios años de uso, en parte por un pitido molesto que se presentaba al activar ANC.

Está interesado especialmente en la calidad del sonido, la ecualización, las mejoras de fidelidad y la posibilidad de controlar sus audífonos desde el Mac y desde Android.

Investigamos XM6 Control, un proyecto independiente de código abierto que proporciona controles Sony desde macOS.

Después revisamos sus forks, descubrimos mejoras relevantes y decidimos aprovechar el trabajo existente para crear una alternativa más completa.

### Decisiones tomadas

1. Desarrollar dos aplicaciones: macOS y Android.
2. Priorizar aplicaciones nativas, eficientes y minimalistas.
3. Utilizar XM6 Control como base principal de macOS.
4. Integrar selectivamente mejoras de sus forks.
5. Incorporar AutoEQ y gestión avanzada de perfiles.
6. Permitir el control del ecualizador interno de los audífonos desde ambas plataformas.
7. Investigar la incorporación de procesamiento DSP avanzado, especialmente en macOS.
8. Mantener compatibilidad y convivencia con Sony Sound Connect.
9. No depender de servidores, suscripciones ni cuentas para las funciones principales.
10. Favorecer código mantenible, probado y reutilizable.

No existe todavía un fork nuestro, ni aplicaciones construidas por nosotros. El trabajo anterior consistió en investigación y diseño preliminar.

---

# 3. REPOSITORIOS PRINCIPALES

Debes inspeccionar estos repositorios directamente. No te bases exclusivamente en sus README: revisa código, ramas, commits, issues, pull requests, licencias y cambios recientes.

## 3.1. Repositorio principal — XM6 Control

https://github.com/ruimartins23/xm6-control

Este será nuestro punto de partida para macOS.

Es una aplicación Swift/SwiftUI que se comunica directamente con los WH-1000XM6 mediante Bluetooth RFCOMM y el protocolo Sony MDR.

### Funcionalidades ya documentadas

- ANC encendido y apagado.
- Modo ambiente y ajuste de intensidad.
- Focus on Voice.
- Ecualizador interno de 10 bandas.
- Presets nativos de Sony.
- Modos Standard, Background Music y Cinema.
- Ajustes de música de fondo.
- Estado de batería y carga.
- Dispositivos multipunto.
- Cambio del dispositivo de reproducción.
- Speak-to-Chat.
- Detección de uso.
- Apagado automático.
- Controles desde la barra de menús.
- Panel flotante.
- Tema claro y oscuro.
- Registro de mensajes del protocolo para depuración.

### Arquitectura existente

Su código se divide principalmente en:

**SonyHeadphonesKit**
- Codificación y decodificación del protocolo.
- Parser de paquetes.
- Comandos y opcodes.
- Transporte RFCOMM.
- Modelo de estado.
- Control de sesiones Bluetooth.

**XM6Control**
- Interfaz SwiftUI.
- Dashboard.
- Panel de barra de menús.
- Widget flotante.
- Controles visuales.

No reescribas componentes funcionales sin motivos técnicos demostrables.

Primero compila la base original y confirma qué partes pueden reutilizarse.

---

# 4. FORKS QUE DEBEMOS EVALUAR E INTEGRAR

## 4.1. Anneo22 — Gestión inteligente de conexión Bluetooth

Repositorio:

https://github.com/Anneo22/xm6-control

Pull request:

https://github.com/ruimartins23/xm6-control/pull/4

Rama de trabajo documentada:

`release-channel-when-idle`

### Problema que resuelve

Los WH-1000XM6 pueden aceptar solamente una sesión de control Sony MDR a la vez.

Si una aplicación mantiene ocupado el canal Bluetooth de control, otra aplicación, como Sony Sound Connect en Android, puede encontrar dificultades para comunicarse con los audífonos.

Esto es especialmente importante porque nuestro producto funcionará en Mac y Android.

### Cambios que queremos incorporar

- Conexión al canal de control bajo demanda.
- Liberación del canal aproximadamente 20 segundos después de terminar su uso.
- Conexión automática antes de enviar comandos.
- Cola de operaciones pendientes.
- Manejo de errores y reintentos controlados.
- Desconexión inmediata cuando el usuario lo solicite.
- Detección de cesión del control a otro dispositivo.
- Evitar reconexiones agresivas cuando el celular esté controlando los audífonos.
- Lectura correcta del estado ANC/ambiente en firmware 3.x.
- Pruebas automatizadas del ciclo de vida Bluetooth.

### Precaución importante

La revisión del PR identificó un posible problema con la detección del estado inicial de las ventanas visibles.

Debes corregir o rediseñar esa parte y añadir pruebas para evitar conexiones involuntarias.

No hagas merge automático de toda la rama sin revisar su compatibilidad.

Esta mejora es **prioridad crítica**.

---

## 4.2. yelloduxx — Nueva interfaz y widgets reales

Repositorio:

https://github.com/yelloduxx/xm6-control

Pull request:

https://github.com/ruimartins23/xm6-control/pull/6

Rama documentada:

`macos27-redesign`

### Mejoras interesantes

- Rediseño de la interfaz con SwiftUI y Liquid Glass.
- Widgets reales mediante WidgetKit.
- Widgets pequeños, medianos y grandes.
- Visualización de batería y modos de sonido.
- Cambios de ANC y listening mode desde widgets.
- Presets de ecualización desde el widget grande.
- Ajustes organizados en una ventana nativa.
- Inicio automático con macOS.
- Modo demostración sin hardware.
- Corrección del cargador infinito de dispositivos multipunto.
- Actualización visual del estado ANC cuando se utilizan los botones físicos.
- Mejoras de construcción y firma de extensiones.

### Decisión de integración

Quiero aprovechar especialmente:

1. El sistema de widgets WidgetKit.
2. Los cambios de estado sincronizados con el hardware.
3. El manejo de errores de multipunto.
4. El inicio automático.
5. El modo demostración.
6. Los componentes visuales que aporten valor.

No adoptes automáticamente todo el rediseño.

La aplicación final debe tener identidad visual propia, mantener una arquitectura limpia y funcionar con la versión de macOS que utilicemos como objetivo.

Considera que WidgetKit funciona en procesos separados. Diseña correctamente la comunicación entre aplicación principal y extensiones mediante mecanismos seguros y compatibles con la firma del proyecto.

Comprueba especialmente la interacción de los widgets con el gestor Bluetooth de Anneo22.

---

## 4.3. plagnioj — Centro de Control de macOS

Repositorio:

https://github.com/plagnioj/xm6-control

Pull request:

https://github.com/ruimartins23/xm6-control/pull/1

Propuso agregar controles nativos de macOS para ANC, modo ambiente, desactivación y apertura de la aplicación.

La propuesta original presenta problemas técnicos documentados por el mantenedor, relacionados con la comunicación entre procesos y el almacenamiento compartido.

### Instrucciones

- No fusionar directamente el código de la extensión.
- Estudiar su arquitectura e ideas.
- Recuperar los componentes útiles y técnicamente válidos.
- Si implementas controles de sistema, utilizar comunicación entre procesos comprobada.
- No marcar los controles como funcionales sin pruebas reales.
- Verificar si las correcciones de compilación ya están presentes en la rama principal.

Prioridad secundaria respecto a estabilidad Bluetooth y ecualización.

---

## 4.4. mlazzarotto — Compatibilidad Swift

Repositorio:

https://github.com/mlazzarotto/xm6-control

Pull request:

https://github.com/ruimartins23/xm6-control/pull/2

Corrige un problema del manifiesto SwiftPM con versiones modernas de Swift y sus herramientas.

Este PR fue integrado en la rama principal el 29 de julio de 2026.

### Instrucciones

- Verificar que esté presente en la versión base.
- No duplicar modificaciones ya incorporadas.
- Mantener compatibilidad con la herramienta de compilación elegida.

---

# 5. OTROS REPOSITORIOS QUE DEBEMOS APROVECHAR

## 5.1. ParaEQ — Ecualización paramétrica avanzada

https://github.com/wabsto1/ParaEQ

Proyecto macOS que implementa procesamiento de audio de todo el sistema mediante Core Audio, Accelerate y vDSP.

Funciones documentadas:

- Ecualización paramétrica.
- Configuraciones de 5, 10, 15 y 31 bandas.
- Filtros peak y shelving.
- Frecuencia, ganancia y factor Q.
- Filtros FIR y convolución.
- Procesamiento independiente por canal.
- Preamplificación y limitador.
- AutoEQ.
- Visualizador de espectro en tiempo real.
- Curvas de respuesta.
- Comparación A/B.
- Historial de cambios.

### Nuestra decisión

Estudiar y reutilizar selectivamente los componentes apropiados para incorporar un **modo avanzado de ecualización DSP en macOS**.

No mantener dos motores de audio completos si pueden integrarse de manera limpia.

Diferenciar cuidadosamente:

- Ecualización interna de Sony.
- Ecualización DSP aplicada por el sistema operativo.

El usuario debe saber qué tipo de modificación está realizando.

Evitar que dos ecualizadores se acumulen involuntariamente y generen un sonido distinto del esperado.

Verificar la arquitectura de captura, latencia, selección de salida, cambio de dispositivos y seguridad de escucha.

---

## 5.2. AutoEq — Mediciones y correcciones acústicas

https://github.com/jaakkopasanen/AutoEq

Proyecto de referencia para calcular correcciones de respuesta en frecuencia basadas en mediciones.

Archivo específico identificado para WH-1000XM6:

https://github.com/jaakkopasanen/AutoEq/blob/master/results/Kuulokenurkka/over-ear/Sony%20WH-1000XM6/Sony%20WH-1000XM6%20ParametricEQ.txt

Este archivo contiene un perfil paramétrico de 10 filtros y una preamplificación de -4.4 dB.

### Integraciones propuestas

- Importación de perfiles AutoEQ.
- Visualización de curvas de frecuencia.
- Conversión de perfiles paramétricos a formatos internos.
- Optimización de una curva para el ecualizador de 10 bandas de Sony.
- Importación/exportación de presets.
- Validación de mediciones y fuentes.
- Diferentes objetivos de respuesta sonora.

### Restricción técnica fundamental

Los 10 controles internos de los Sony no equivalen a 10 filtros paramétricos arbitrarios.

No copies directamente las frecuencias y los factores Q de AutoEQ como si fueran parámetros disponibles en el hardware.

Debes obtener las frecuencias centrales, límites y comportamiento real del ecualizador Sony.

Después, implementar una aproximación matemática de la curva objetivo a las bandas disponibles, preferiblemente minimizando el error de respuesta con ponderaciones apropiadas.

Mostrar la diferencia entre la curva objetivo y la curva aproximada, sin prometer una equivalencia perfecta.

Separar los perfiles científicos medidos de los perfiles subjetivos diseñados por preferencia.

Revisar licencias y atribuciones de los datos de medición, que pueden tener condiciones diferentes de la licencia del software AutoEq.

---

## 5.3. SonyHeadphonesClient original

https://github.com/Plutoberth/SonyHeadphonesClient

Proyecto anterior de ingeniería inversa del protocolo de los audífonos Sony.

Actualmente archivado.

Utilizarlo como referencia histórica del protocolo y sus estructuras.

No tomarlo como base principal para los XM6 modernos.

---

## 5.4. Fork de mos9527 — SonyHeadphonesClient

https://github.com/mos9527/SonyHeadphonesClient

Este fork amplía las implementaciones del protocolo Sony y ha sido referenciado por el propio XM6 Control por la documentación de nuevas familias de comandos.

Investigar:

- Implementación de Sony MDR.
- Familias modernas de comandos.
- Formato de paquetes.
- Comandos disponibles para generaciones XM6.
- Secuencia y confirmación de mensajes.
- Tratamiento de versiones de firmware.
- Funcionalidades que el XM6 Control todavía no soporte.

Reutilizar solamente componentes verificados y compatibles con nuestras licencias.

---

## 5.5. Gadgetbridge — Referencia para Android

Repositorio:

https://codeberg.org/Freeyourgadget/Gadgetbridge

Documentación Sony:

https://gadgetbridge.org/gadgets/headphones/sony/

Gadgetbridge incluye una implementación Android de funciones de control de audífonos Sony.

Su compatibilidad XM6 figura como parcial y experimental; el ecualizador XM6 no está documentado como funcional.

### Cómo utilizarlo

- Investigar el transporte Bluetooth Android.
- Examinar cómo gestiona sesiones y reconexiones.
- Revisar el protocolo Sony MDR.
- Aprender de su manejo de permisos y estados.
- Comparar los comandos con el código macOS.

**No asumir que su implementación XM6 ya resuelve el ecualizador.**

Revisar especialmente sus condiciones de licencia antes de copiar código. Si hay obligaciones incompatibles, utilizarlo únicamente como referencia técnica y desarrollar una implementación propia.

---

## 5.6. Proyectos adicionales de contraste

Investigar también:

https://github.com/ibatra/sony-headphones-client

Implementación alternativa con Rust y Tauri cuyo estado de macOS es limitado, pero que puede aportar ideas sobre transporte multiplataforma y documentación del protocolo.

https://github.com/shellingtonshreyas/xm6-macos-controller

Implementación independiente de control para XM6 en macOS. Comparar su tratamiento de la conexión Bluetooth, diagnóstico, empaquetado y pruebas.

Estos dos proyectos son referencias secundarias. No cambies la arquitectura principal sin demostrar que ofrecen una ventaja importante.

---

# 6. OBJETIVOS FUNCIONALES DEL PRODUCTO

El producto tendrá dos módulos conceptuales:

## A. Control de los audífonos

Funciones que modifican directamente la configuración del Sony WH-1000XM6.

## B. Audio Studio

Funciones de ecualización, perfiles, análisis, optimización y personalización.

Ambas aplicaciones compartirán la misma identidad visual, los mismos formatos de presets y una experiencia coherente.

---

# 7. FUNCIONALIDADES OBLIGATORIAS PARA MAC Y ANDROID

## 7.1. Conexión y estado

- Detectar audífonos XM6 emparejados.
- Conectarse al canal de control Bluetooth.
- Mostrar conectado, desconectado, ocupado y error.
- Mostrar batería y estado de carga.
- Leer el estado inicial real de los audífonos.
- Actualizar el estado después de acciones físicas.
- Reconectar solamente cuando sea apropiado.
- Liberar el canal de control cuando no esté en uso.
- Respetar el uso simultáneo de Mac y Android.

No asumir que conexión de audio Bluetooth y conexión al canal Sony MDR son lo mismo.

## 7.2. Controles Sony

Implementar o conservar las funciones verificadas:

- ANC.
- Modo ambiente.
- Nivel de ambiente.
- Focus on Voice.
- Listening Mode.
- Speak-to-Chat.
- Detección de uso.
- Apagado automático.
- Estado de batería.
- Multipunto cuando sea posible.
- Selección de reproducción cuando sea compatible.
- Presets de ecualización Sony.

Toda función debe pasar por una capa de capacidades.

Si una versión de firmware no soporta algo o un comando no está confirmado, mostrar el control como no disponible en lugar de enviar instrucciones inventadas.

## 7.3. Ecualizador interno de 10 bandas

Implementar en ambas plataformas:

- Diez controles de banda.
- Valores y límites reales.
- Frecuencias correctas.
- Modificación en tiempo real, con limitación de frecuencia de comandos.
- Lectura y restauración del estado.
- Restaurar configuración plana, cuando sea posible.
- Duplicar un preset.
- Comparación entre presets.
- Guardar ilimitados perfiles dentro de nuestra aplicación.
- Enviar un perfil al ecualizador interno Sony.
- Identificar claramente el perfil actualmente activo.

Importante: guardar ilimitados perfiles en nuestra aplicación no implica que el firmware Sony pueda mantener ilimitados presets simultáneos.

---

# 8. AUTOEQ Y PERFILES INTELIGENTES

Este módulo es una de las funcionalidades principales que nos diferenciarán de Sony Sound Connect.

## 8.1. Perfiles iniciales sugeridos

- Original Sony.
- Neutro / Referencia.
- AutoEQ XM6.
- Graves controlados.
- Voces claras.
- Música electrónica.
- Rock.
- Música clásica.
- Películas.
- Videojuegos.
- Podcast.
- Personalizado.

Los perfiles comerciales o subjetivos deberán identificarse como tales, no como correcciones científicas comprobadas.

## 8.2. Modos AutoEQ

### AutoEQ interno

Convierte una curva de corrección a los 10 controles Sony disponibles.

Debe funcionar con ambos clientes.

### AutoEQ DSP

Aplica una corrección más precisa utilizando filtros paramétricos.

Inicialmente será una función de macOS, con evaluación separada para Android.

## 8.3. Herramientas de ecualización

- Gráfica de respuesta en frecuencia.
- Visualización de puntos de bandas.
- Comparación curva objetivo vs curva aproximada.
- Vista antes/después.
- Selección de perfiles.
- Restablecimiento rápido.
- Importar y exportar configuraciones.
- Historial de cambios.
- Indicadores de riesgo de saturación.
- Distinción entre configuración real del dispositivo y simulación visual.

La curva mostrada debe indicar si proviene de mediciones, predicción matemática o procesamiento DSP. No presentar una curva simulada como medición acústica real de los audífonos del usuario.

---

# 9. MODO AVANZADO PARA macOS

Integrar progresivamente un motor DSP basado en ParaEQ cuando la capa principal sea estable.

Características deseadas:

- Ecualizador paramétrico.
- Bandas ajustables.
- Filtros peak, low shelf y high shelf.
- Ganancia.
- Frecuencia central.
- Factor Q.
- Preamplificación.
- Protección contra clipping.
- A/B.
- Modo bypass.
- Corrección AutoEQ precisa.
- Ajuste independiente de canales cuando sea compatible.
- Analizador de espectro.
- Procesamiento para audio del sistema cuando macOS lo permita.
- Detección de cambios de dispositivo de salida.

### Restricciones

No introducir latencia excesiva.

No generar bucles de captura y reproducción.

No cambiar silenciosamente la salida de audio del usuario.

No aplicar ecualización DSP a dispositivos que no sean los seleccionados.

El motor DSP debe poder activarse y desactivarse completamente.

Las funciones críticas del control XM6 no pueden depender del motor DSP.

---

# 10. APP ANDROID — ALCANCE REALISTA

Desarrollar una aplicación Android nativa con Kotlin y Jetpack Compose.

El control interno de los XM6 será prioridad.

Investigar cuidadosamente:

- Bluetooth Classic y RFCOMM.
- Permisos de conexión Bluetooth.
- Restricciones de ejecución en segundo plano.
- Ciclo de vida de servicios.
- Descubrimiento de servicios.
- Integración con dispositivos ya emparejados.
- Compatibilidad con distintas versiones Android.
- Manejo de reconexiones.
- Estado de conexión y multiplexación con Mac.
- Soporte de widgets y Quick Settings en una fase posterior.

No depender de root.

No exigir desactivar Sony Sound Connect permanentemente.

No alterar el emparejamiento existente sin consentimiento.

### DSP Android

Evaluar Android AudioEffect, Equalizer y DynamicsProcessing.

No prometer ecualización paramétrica global para todas las aplicaciones Android: los efectos sobre la mezcla global tienen restricciones y comportamientos diferentes según fabricante, versión de Android y ruta de audio.

Para la primera versión, el ecualizador interno XM6 y AutoEQ adaptado a sus bandas serán suficientes.

El DSP avanzado para Android será experimental hasta demostrar compatibilidad real.

---

# 11. ARQUITECTURA PROPUESTA

Prefiero una arquitectura nativa, modular y sin sobreingeniería.

## Estructura recomendada

```text
xm6-audio-studio/
│
├── macos/
│   ├── App/
│   ├── SonyHeadphonesKit/
│   ├── Bluetooth/
│   ├── Features/
│   │   ├── Dashboard/
│   │   ├── Equalizer/
│   │   ├── NoiseControl/
│   │   ├── Multipoint/
│   │   └── Settings/
│   ├── AudioDSP/
│   ├── Widgets/
│   └── Tests/
│
├── android/
│   ├── app/
│   ├── bluetooth/
│   ├── protocol/
│   ├── domain/
│   ├── data/
│   ├── features/
│   └── tests/
│
├── shared/
│   ├── protocol-spec/
│   ├── protocol-fixtures/
│   ├── preset-schema/
│   ├── autoeq-test-vectors/
│   └── design-tokens/
│
├── docs/
│   ├── architecture/
│   ├── bluetooth/
│   ├── dsp/
│   ├── decisions/
│   ├── compatibility/
│   └── testing/
│
└── README.md
```

Puedes adaptar esta estructura después de analizar el código real. La prioridad es conservar la compatibilidad con la aplicación base y lograr independencia entre capas.

## Decisión sobre código compartido

Swift y Kotlin tendrán implementaciones de transporte nativas.

Para evitar divergencias:

- Mantener una especificación común del protocolo Sony.
- Compartir vectores de prueba de paquetes.
- Compartir esquemas JSON de presets.
- Compartir definiciones de capacidades y estados.
- Compartir las fórmulas matemáticas documentadas.
- Ejecutar pruebas de conformidad sobre ambas implementaciones.

No introducir Kotlin Multiplatform, Rust FFI o un framework multiplataforma solo por compartir unas pocas clases.

Evalúa reutilización de código real donde el beneficio compense la complejidad.

---

# 12. PROTOCOLO BLUETOOTH SONY

XM6 Control documenta el uso del protocolo propietario Sony MDR sobre Bluetooth RFCOMM.

UUID de servicio documentado:

`96CC203E-5068-46AD-B32D-E316F5E069BA`

Debes inspeccionar el código antes de generar comandos.

Analizar:

- Estructura de frame.
- Delimitadores.
- Escaping.
- Checksums.
- Números de secuencia.
- ACK.
- Reensamblado de mensajes fragmentados.
- Tablas de opcodes.
- Familias de comandos antiguas y modernas.
- Solicitudes de lectura y escritura.
- Notificaciones espontáneas del hardware.
- Control de errores y timeouts.

### Problema de firmware conocido

En pruebas documentadas por Anneo22, firmware 3.0.0 responde a determinadas consultas de ANC con información incompleta.

El estado real puede recuperarse mediante una familia alternativa de eventos o consultas.

Investigar específicamente los subtipos `0x17` y `0x19` y sus diferencias.

No asumir que las respuestas son iguales en todos los firmwares.

### Reglas de seguridad

- No enviar comandos desconocidos de escritura.
- No experimentar con comandos de actualización de firmware.
- No restablecer el dispositivo de fábrica automáticamente.
- No borrar emparejamientos para solucionar problemas sin autorización.
- No saturar el canal con escrituras continuas.
- Conservar logs de diagnóstico sin información personal sensible.
- Usar timeout y cancelación de operaciones.
- Soportar recuperación de fallos de conexión.

---

# 13. COEXISTENCIA ENTRE MAC Y ANDROID

El usuario normalmente podrá conectar los XM6 a dos dispositivos mediante multipunto, pero esto no significa que dos aplicaciones puedan mantener simultáneamente el canal de control Sony.

El administrador de conexión deberá distinguir:

1. Audio Bluetooth.
2. Canal de control MDR.
3. Estado y propiedad de la sesión.
4. Dispositivo que actualmente tiene el control.

### Comportamiento deseado

Cuando el usuario abre el ecualizador en Mac:

- La aplicación adquiere el canal.
- Lee el estado actual.
- Permite realizar cambios.
- Espera el período de inactividad configurado.
- Libera el canal.

Cuando el usuario abre Android:

- Puede adquirir el control cuando esté disponible.
- Lee el estado actualizado.
- No restaura automáticamente un preset obsoleto.
- Permite cambiar configuraciones.
- Libera el canal cuando corresponda.

El dispositivo que recupera el control siempre debe leer el estado real antes de modificarlo.

Añadir protección contra conflictos, acciones repetidas y cambios hechos desde Sony Sound Connect.

La sincronización de perfiles entre nuestras aplicaciones es un problema diferente de la conexión Bluetooth con los audífonos.

En la primera versión usar un formato común exportable/importable, sin backend obligatorio.

La sincronización automática futura podrá realizarse por mecanismos locales u opcionales, previa evaluación de seguridad.

---

# 14. DISEÑO Y EXPERIENCIA DE USUARIO

El objetivo visual es una aplicación moderna, premium, minimalista y profesional.

Inspiración:

- Aplicaciones nativas de Apple.
- Interfaces de audio profesional.
- Gráficas limpias de ecualización.
- Controles grandes, precisos y fáciles de manipular.
- Uso moderado de transparencias.
- Excelente contraste.

No copiar imágenes, marcas o diseño propietario de Sony sin derechos de uso.

## Pantalla principal

Debe incluir:

- Estado de conexión XM6.
- Batería.
- Indicador de ANC.
- Modo de escucha.
- Preset activo.
- Acceso destacado al ecualizador.
- Gestión de perfiles.
- Acceso a opciones avanzadas.

## Pantalla de ecualización

Debe convertirse en la sección más trabajada visualmente.

Propuesta:

- Gráfica grande de respuesta de frecuencia.
- Controles de bandas.
- Selector de perfil.
- Botón A/B.
- AutoEQ.
- Restaurar ajustes.
- Información sobre el destino de la ecualización.

Dos modos claros:

**EQ de audífonos:** se guarda en el hardware Sony.

**EQ avanzado:** se procesa en el dispositivo local, cuando exista un motor DSP disponible.

No utilizar una sola interfaz que haga imposible saber qué está modificando el usuario.

## Identidad multiplataforma

Compartir:

- Paleta de colores.
- Tipografía conceptual.
- Iconografía.
- Estructura de navegación.
- Nombres de ajustes.
- Esquema de perfiles.

Utilizar convenciones nativas de cada plataforma en lugar de imitar por completo la interfaz del otro sistema.

---

# 15. CRITERIOS DE CALIDAD

No aceptes una implementación donde la interfaz funciona con datos simulados pero no existe la comunicación Bluetooth real.

Tampoco reportes conexión física exitosa cuando solamente se ejecutaron pruebas unitarias.

Cada funcionalidad debe etiquetarse internamente como:

- Implementada y probada con hardware.
- Implementada y probada con simulación.
- Implementada, pendiente de pruebas reales.
- Experimental.
- Pendiente.
- No compatible.

## Pruebas mínimas

### Bluetooth

- Conexión inicial.
- Desconexión inesperada.
- Ausencia de permisos.
- Audífonos apagados.
- Canal ocupado.
- Conflicto con Sound Connect.
- Recuperación de conexión.
- Comandos pendientes.
- Reintentos controlados.
- Cambio entre Mac y Android.

### Protocolo

- Codificación/decodificación de frames.
- Checksums correctos e incorrectos.
- ACK y secuencias.
- Frames fragmentados.
- Paquetes desconocidos.
- Opcodes de ambas tablas.
- Interpretación de estados.

### Ecualizador

- Escritura de las 10 bandas.
- Lectura y validación.
- Guardar presets.
- Recuperar presets.
- Exportación/importación.
- Restauración de estados.
- Cambio de preset durante reproducción.
- Pruebas numéricas de AutoEQ.
- Limitar valores al rango aceptado.

### macOS DSP

- Bypass.
- Ganancia unitaria.
- Protección contra clipping.
- Cambios de frecuencia de muestreo.
- Cambios de salida.
- Latencia y consumo.
- Validación de coeficientes.
- Comparación de resultados con señales de prueba.

### UI

- Tema claro/oscuro.
- Redimensionamiento.
- Accesibilidad.
- Estados vacíos.
- Errores.
- Permisos.
- Widgets.
- Persistencia de preferencias.

---

# 16. SEGURIDAD, LICENCIAS Y PRIVACIDAD

Antes de incorporar código externo:

- Revisar la licencia de cada repositorio.
- Preservar copyright y avisos requeridos.
- Mantener un registro de componentes externos.
- Documentar cambios propios.
- Revisar licencias de dependencias y mediciones AutoEQ.
- Evitar incorporar código con obligaciones incompatibles sin una decisión explícita.
- No utilizar APIs privadas sin evaluar las consecuencias técnicas y de distribución.

La aplicación debe poder funcionar localmente, sin cuenta y sin transmisión de datos a servidores.

No recolectar historiales de escucha ni telemetría innecesaria.

Evitar logos Sony que puedan hacer pensar que la aplicación es oficial.

Añadir un aviso de proyecto independiente no afiliado a Sony.

Mantener Sony Sound Connect como herramienta oficial para firmware y funciones propietarias no implementadas.

---

# 17. ESTRATEGIA DE DESARROLLO

No desarrollar toda la aplicación en un único cambio enorme.

Trabajar por entregas verticales, con código funcional y verificable.

## Etapa 0 — Auditoría

1. Inspeccionar el repositorio principal.
2. Revisar ramas y PR actuales.
3. Comparar los forks contra upstream.
4. Crear matriz de funcionalidades y riesgos.
5. Revisar licencias.
6. Identificar componentes comunes.
7. Confirmar las versiones de SDK.
8. Definir interfaces entre módulos.
9. Registrar decisiones técnicas en documentos ADR.
10. Crear un backlog priorizado.

## Etapa 1 — Fundación del proyecto

- Crear monorepo.
- Preparar macOS desde XM6 Control.
- Crear proyecto nativo Android.
- Crear modelos de datos compartidos.
- Establecer el formato de presets.
- Preparar pruebas y CI.
- Implementar modo demostración en ambas plataformas.

## Etapa 2 — Comunicación Bluetooth de ambos sistemas

### Mac

- Conservar transporte original.
- Incorporar control de sesión bajo demanda.
- Arreglar conflictos y lecturas de estado.
- Validar comandos actuales.

### Android

- Implementar conexión RFCOMM.
- Portar el protocolo Sony MDR con referencia en el código verificado.
- Implementar lectura del estado.
- Implementar control de sesión equivalente.

Esta etapa debe producir las primeras versiones que puedan interactuar realmente con los audífonos.

## Etapa 3 — Ecualizador compartido

- Ecualizador interno de 10 bandas en ambas apps.
- Lectura/escritura verificada.
- Gestión local de presets.
- Exportación/importación.
- Gráficas y estados.
- Pruebas de paridad Mac/Android.

## Etapa 4 — AutoEQ

- Importar perfil XM6.
- Dibujar curva objetivo.
- Aproximar curva a las bandas Sony.
- Comparar resultados.
- Enviar preset al hardware.
- Documentar la precisión y limitaciones.

## Etapa 5 — Mejoras específicas de macOS

- Integrar widgets estables.
- Mejorar UI.
- Incorporar inicio automático.
- Implementar EQ paramétrico utilizando ParaEQ cuando sea técnicamente viable.
- Añadir analizador en tiempo real.

## Etapa 6 — Mejoras específicas Android

- Controles rápidos.
- Widget Android.
- Gestión de estados en segundo plano.
- Experimentos DSP opcionales.
- Mejoras de integración con el sistema.

## Etapa 7 — Estabilización y distribución

- Revisar pruebas.
- Pruebas con hardware real.
- Corregir fallos.
- Optimizar rendimiento y batería.
- Generar .app o .dmg para macOS.
- Generar APK instalable Android.
- Documentar instalación y permisos.
- Preparar versiones reproducibles.

**Importante:** la primera entrega completa deberá contener dos aplicaciones, no solamente la de macOS. Las etapas 5 y 6 pueden desarrollarse paralelamente una vez que ambas aplicaciones controlen el hardware.

---

# 18. ENTREGABLES OBLIGATORIOS

Espero recibir:

1. Repositorio organizado con el código fuente.
2. Aplicación macOS.
3. Aplicación Android.
4. Proyecto compilable con instrucciones.
5. APK de pruebas cuando el entorno lo permita.
6. Bundle macOS cuando el entorno lo permita.
7. Pruebas automatizadas.
8. Documentación de arquitectura.
9. Especificación compartida de protocolo.
10. Información de licencias.
11. Registro de cambios.
12. Lista de funciones implementadas y pendientes.
13. Procedimiento para probar con mis WH-1000XM6.
14. Problemas conocidos y riesgos.
15. Instrucciones para continuar desarrollando con otros agentes o sesiones de Cowork.

No declares entregado un binario que no se haya compilado correctamente.

Si el entorno no permite compilar una plataforma, deja el código preparado, valida todo lo que puedas y documenta exactamente la limitación.

---

# 19. REGLAS DE TRABAJO DEL AGENTE

- Investiga antes de reescribir.
- Reutiliza trabajo de calidad existente.
- No hagas merges ciegos.
- Mantén commits pequeños y reversibles.
- Ejecuta pruebas después de cambios significativos.
- No inventes resultados de hardware.
- No inventes comandos Bluetooth.
- No reemplaces funcionalidad real por simulaciones silenciosas.
- No añadas dependencias por comodidad si no son necesarias.
- Prefiere arquitectura mantenible a abstracciones excesivas.
- Documenta cualquier decisión que afecte a ambas plataformas.
- Prioriza funcionamiento real sobre efectos visuales.
- Evita cambios que puedan dañar el dispositivo.
- Conserva la capacidad de utilizar Sony Sound Connect.
- Si hay problemas técnicos, busca una solución sólida en lugar de ocultarlos.
- Mantén el código preparado para futuras extensiones.

---

# 20. PRIMERA TAREA QUE DEBES EJECUTAR

Comienza inmediatamente realizando lo siguiente:

**A.** Clona y analiza el repositorio original XM6 Control.

**B.** Compara las ramas y los cambios de Anneo22, yelloduxx, plagnioj y mlazzarotto.

**C.** Examina ParaEQ, AutoEq, SonyHeadphonesClient, mos9527 y Gadgetbridge, diferenciando código reutilizable, especificaciones de protocolo y referencias conceptuales.

**D.** Establece una arquitectura definitiva para entregar macOS y Android, con un protocolo compartido a través de especificaciones y pruebas.

**E.** Crea la estructura del proyecto y un backlog ordenado por dependencias.

**F.** Compila la aplicación macOS original y prepara su evolución.

**G.** Crea el proyecto Android e implementa los primeros componentes de protocolo y conexión Bluetooth.

**H.** Prepara pruebas automatizadas que permitan verificar que ambas aplicaciones interpretan las mismas respuestas Sony.

**I.** Continúa con los controles y el ecualizador interno de 10 bandas en ambas plataformas.

No te detengas en el documento de arquitectura si puedes continuar desarrollando dentro del entorno disponible.

Al finalizar cada bloque de trabajo, informa de forma concreta:

- Qué código se creó o modificó.
- Qué repositorios o commits se integraron.
- Qué pruebas se ejecutaron y sus resultados.
- Qué partes necesitan hardware real.
- Qué limitaciones quedan.
- Cuál es el siguiente bloque prioritario.

## OBJETIVO FINAL

Construir **XM6 Audio Studio**, una solución de control y ecualización de calidad profesional para Sony WH-1000XM6, compuesta inicialmente por dos aplicaciones nativas para macOS y Android.

Debe combinar lo mejor del trabajo comunitario existente con nuevas funcionalidades de ecualización avanzada, AutoEQ, perfiles personalizables, convivencia Bluetooth entre dispositivos y una interfaz minimalista de alta calidad.

El proyecto debe ser real, mantenible, extensible, seguro y verificable. No es solamente una demostración visual ni una propuesta teórica.

**Empieza la auditoría del código y continúa con la implementación.**

# ACTUALIZACIÓN DEL PROYECTO — Nombre y compatibilidad futura

## Nombre oficial

A partir de ahora, el software se llamará **XM Audio Studio**.

Actualizar el nombre del proyecto, aplicaciones, identificadores internos, documentación y repositorio principal a `xm-audio-studio`, preservando las atribuciones de código correspondientes.

## Nueva etapa final: compatibilidad con toda la serie Sony 1000X de diadema

Aunque el desarrollo inicial se concentra en los Sony WH-1000XM6 para macOS y Android, la arquitectura debe diseñarse desde el comienzo para incorporar posteriormente:

- MDR-1000X (primera generación).
- WH-1000XM2.
- WH-1000XM3.
- WH-1000XM4.
- WH-1000XM5.
- WH-1000XM6.

### Requisitos de arquitectura

1. Crear una interfaz `HeadphoneDevice` independiente del modelo.
2. Implementar controladores o adaptadores específicos para las diferentes generaciones y variantes del protocolo Sony MDR.
3. Detectar automáticamente el modelo conectado y, cuando sea posible, su firmware.
4. Incorporar una matriz de capacidades por modelo: ANC, ambiente, EQ, batería, multipunto y demás funciones.
5. Adaptar el ecualizador a la cantidad de bandas y los parámetros realmente disponibles en cada generación. No asumir que todos tienen 10 bandas.
6. Permitir perfiles AutoEQ específicos por modelo, evitando aplicar automáticamente una corrección acústica de XM6 a unos XM4.
7. Preparar pruebas de protocolo compartidas entre Mac y Android.
8. Investigar Sony Device Center como referencia técnica adicional: https://github.com/marconvcm/sony-device-center
9. Mantener separación entre las funciones compatibles, experimentales y no disponibles.
10. No declarar soporte completo para una generación sin validación en hardware real.

### Plan de implementación

Primero completar y estabilizar XM Audio Studio para WH-1000XM6 en macOS y Android.

En la etapa final, ampliar progresivamente a XM5, XM4, XM3, XM2 y MDR-1000X, priorizando la reutilización de protocolos y realizando pruebas independientes para cada generación.

El proyecto debe quedar preparado para incorporar también la serie WF en el futuro, pero los modelos intraurales quedan fuera del alcance inicial.

**Objetivo:** desarrollar XM Audio Studio como una plataforma extensible de control y ecualización para diferentes generaciones de audífonos Sony, sin sacrificar la compatibilidad ni las funciones avanzadas de los WH-1000XM6.
