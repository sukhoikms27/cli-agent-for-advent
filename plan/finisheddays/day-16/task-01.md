# T1 — Бамп toolchain (gate)

## Цель
Подготовить сборку к потреблению MCP Kotlin SDK 0.13.0 (собран на Kotlin 2.3.21, пинит
serialization 1.11.0, coroutines 1.11.0, kotlinx-io 0.9.0). Компилятор 2.1.21 не читает metadata 2.3.21.
Это gate-задача: выполняется и проверяется первой, изолированно, БЕЗ MCP-кода.

## Изменения

### `build.gradle.kts`
- `kotlin("jvm") version "2.1.21"` → `"2.3.21"`
- `kotlin("plugin.serialization") version "2.1.21"` → `"2.3.21"` (версия плагина = версия компилятора)
- `val kotlinxSerializationVersion = "1.8.1"` → `"1.11.0"`
- `val kotlinxCoroutinesVersion = "1.10.2"` → `"1.11.0"`
- Новый: `val kotlinIoVersion = "0.9.0"` + `implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinIoVersion")`
  (нужен для `.asSource()`/`.asSink()` расширений в T5; объявить явно, не полагаться на транзитив)
- Новый: `runtimeOnly("org.slf4j:slf4j-simple:2.0.18")` (SDK использует `io.github.oshai:kotlin-logging`
  → нужен SLF4J-бэкенд; `runtimeOnly` — не утекает в API)

### `src/main/resources/simplelog.properties` (новый)
```
org.slf4j.simpleLogger.defaultLogLevel=warn
org.slf4j.simpleLogger.log.io.modelcontextprotocol=warn
```
Без этого debug-логи MCP-транспорта засоряют REPL.

## Verify
`./gradlew build` — все существующие тесты зелёные.

**Точки отказа (проверить и при необходимости исправить):**
1. **mockk 1.13.16 vs Kotlin 2.3** — если тесты с mockk падают на этапе компиляции/выполнения,
   бампнуть mockk до актуальной версии, совместимой с Kotlin 2.3.
2. **kotlinx-serialization 1.11.0** — проверить `@SerialName`/polymorphic-сериализаторы в
   `llm/model/` и `memory/` (`ChatData`, `WorkingMemory`, `LongTermMemory`, `TaskState`).
   serialization 1.11 обратно-совместима с 1.8 для потребительского кода, но миграционные заметки
   стоит держать в уме.
3. **kotlinx-coroutines-test 1.11.0** — API `runTest` стабилен; проблем не ожидается.

## Коммит
Изолированный коммит: `chore: bump Kotlin 2.1.21→2.3.21, serialization/coroutines, +kotlinx-io, +slf4j-simple (day16 gate)`.
MCP-кода в этом коммите нет.
