# 02 — ConfigRepository: JSON-загрузка + merge + atomicWrite

## Что сделано

`config/ConfigRepository.kt` — полная переработка под единый `config.json`:

- **Constructor-параметры** (DI-seam для тестов): `configFile` (default `AppPaths.configFile`),
  `localPropertiesFile` (default `./local.properties`).
- **`load()`** — сборка `AppConfig` по приоритету: env > config.json > local.properties.
  - `apiKey`: `CLI_AGENT_API_KEY` > file `apiKey` > `api.key` (required, иначе `error()`).
  - `model`/`baseUrl`/`maxToolRounds`: env override > file > local.properties.
  - `mcp`: **ТОЛЬКО** из config.json; legacy fallback, если массив пуст.
- **`save(config)`** — atomicWrite (temp + `Files.move(ATOMIC_MOVE)`), UTF-8 явно.
- **`addMcpServer(server)`** — путь B (`/mcp add`): replace-by-name, остальные секции сохраняются.
- **`removeMcpServer(name)`** — путь B (`/mcp remove`): возвращает `true/false`.
- **`initFromLegacy()`** — Р3 (`/config init`): генерация стартового config.json из env/properties;
  **не перезаписывает** существующий файл.
- **`loadConfigFile()`** — graceful: битый JSON → warning + defaults (не роняет приложение).

## Legacy fallback (0 регрессий)

```kotlin
private fun legacyMcpServers(localProps: Properties): List<McpServerConfig> {
    // mcp.url + mcp.token (remote) приоритет над mcp.command (stdio)
    // → single-element [{name: "default", ...}]
}
```
Старые single-server конфиги (properties/env) продолжают работать как single-element `mcp`.

## Критерии готовности

- `./gradlew compileKotlin` green.
- `ConfigRepositoryTest` green (16 тестов: JSON-загрузка, merge env/legacy, round-trip,
  add/remove, initFromLegacy, malformed JSON, missing apiKey).
