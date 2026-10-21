# ADR-0002 — Compartir especificación y fixtures, no código

Fecha: 2026-10-21 · Estado: Aceptada

## Contexto
Swift y Kotlin necesitan el mismo protocolo, los mismos modelos y las mismas matemáticas de EQ.
Opciones: Kotlin Multiplatform, Rust FFI, o implementaciones nativas + especificación común.

## Decisión
Implementaciones nativas en cada plataforma, mantenidas en paridad por:
- `shared/protocol-fixtures/frames.json` — generado por un tercer codificador independiente (Python).
- `shared/protocol-fixtures/events.json` — payload → evento canónico (incluye capturas reales).
- `shared/protocol-fixtures/commands.json` — intención → bytes exactos.
- `shared/protocol-spec/models.json` — catálogo de capacidades por modelo.
- `shared/autoeq-test-vectors/vectors.json` — coeficientes RBJ, respuestas y ajustes, generados por
  una implementación de referencia en Python.
- `shared/preset-schema/` — formato de intercambio de perfiles.

Ambas suites (XCTest y JUnit) cargan los mismos archivos. Un cambio en una plataforma que rompa la
paridad rompe su suite.

## Por qué no KMP/Rust
El código compartible es pequeño (~1.500 líneas) y la complejidad de toolchain (FFI, empaquetado,
depuración en dos IDEs) no compensa. El encargo lo desaconseja explícitamente.

## Consecuencias
Doble implementación de la lógica, mitigada por fixtures. Las implementaciones son
deliberadamente "línea a línea" (mismo orden de operaciones y redondeo `floor(x+0.5)`) para que los
resultados numéricos coincidan.
