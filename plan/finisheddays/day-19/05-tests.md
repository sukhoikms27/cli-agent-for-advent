# 05 — Тесты

> 3 новых тест-класса, 21 тест — все по паттернам Day 18 (MockEngine для HTTP-клиента, `@TempDir` для
> хранилища, чистая функция для обработки). **0 регрессий** Day 17/18: суммарно 54 теста в `:mcp-server`.

## Файлы

```
mcp-server/src/test/kotlin/com/cliagent/mcp/server/
├── wikipedia/WikipediaClientTest.kt   # 7 тестов — MockEngine (как WeatherClientTest)
├── notes/NotesStoreTest.kt            # 8 тестов — @TempDir (как WeatherStoreTest)
└── tools/ReportFormatTest.kt          # 6 тестов — чистая функция (как WeatherAggregateTest)
```

## WikipediaClientTest (7 тестов) — MockEngine

Паттерн Day 18 `WeatherClientTest`: `HttpClient(MockEngine { ... })` с роутингом по path.
Различаем opensearch-API (`/w/api.php`) ↔ summary-API (`/api/rest_v1/page/summary`) по `encodedPath`
(как geocoding↔forecast по host в Day 18).

| Тест | Что проверяет |
|---|---|
| `search resolves title via opensearch then fetches summary` | двухшаговый search: opensearch→title→summary→extract+url |
| `search returns null when opensearch finds nothing` | пустой `[]` titles → null |
| `search returns null on opensearch HTTP error` | HTTP 500 → null (не exception) |
| `search returns null when summary has neither extract nor description` | пустой summary → null |
| `search uses ru language section when requested` | `language="ru"` → host `ru.wikipedia.org` |
| `query regex rejects path-like injection` | `../etc`, `a;rm` → null (allowlist до URL) |
| `unknown language falls back to en` | `language="klingon"` → `en.wikipedia.org` |

## NotesStoreTest (8 тестов) — @TempDir

Паттерн Day 18 `WeatherStoreTest`: `NotesStore(@TempDir dir)`, прямые проверки файла на диске.

| Тест | Что проверяет |
|---|---|
| `save creates md file with content` | файл `{name}.md` создан с правильным контентом |
| `save appends md extension only once` | `report.md` не становится `report.md.md` |
| `save overwrites existing file with same name` | повторное save перезаписывает (atomic replace) |
| `slugify sanitizes spaces and special chars` | `Kotlin Digest 2026!` → `kotlin-digest-2026.md` |
| `path-injection attempt collapses to safe filename` | `../etc/passwd` → `etc-passwd.md`, **не выходит за notes/** |
| `blank filename falls back to note` | `"   "` → `note.md` |
| `list returns saved notes sorted by modification desc` | новее первым, корректный размер |
| `list/read + cyrillic → safe` | кириллица схлопывается в safe-имя, read по имени с/без `.md` |

## ReportFormatTest (6 тестов) — чистая функция

Паттерн Day 18 `WeatherAggregateTest`: тестируем `buildReport` (чистая, без IO/времени, дата — параметр).

| Тест | Что проверяет |
|---|---|
| `report starts with title heading and date` | `# Title` + `_27.06.2026_` |
| `each section becomes a numbered heading with its content` | `## Раздел 1`, `## Раздел 2` + контент |
| `section content is trimmed` | пробелы вокруг блока убираются |
| `single section produces one Раздел heading` | нет `## Раздел 2` при одном блоке |
| `empty sections throws IllegalArgumentException` | `require(...)` срабатывает |
| `output is trimmed at the end` | нет trailing newline |

## Сводная таблица (все тесты `:mcp-server`)

| Класс | Тестов | День |
|---|---|---|
| `WeatherStoreTest` | 9 | 18 |
| `WeatherClientTest` | 7 | 18 |
| `WeatherSchedulerTest` | 8 | 18 |
| `WeatherAggregateTest` | 7 | 18 |
| **`WikipediaClientTest`** | **7** | **19 NEW** |
| **`NotesStoreTest`** | **8** | **19 NEW** |
| **`ReportFormatTest`** | **6** | **19 NEW** |
| **Итого** | **52** | (54 с Day 17 integration — см. [`06`](./06-verification.md)) |

## Критерии готовности

- [x] `./gradlew :mcp-server:test` — **52 теста green, 0 failures, 0 errors**.
- [x] 0 регрессий: `WeatherStoreTest`/`WeatherClientTest`/`WeatherSchedulerTest`/`WeatherAggregateTest` — все green.
- [x] Паттерны Day 18 соблюдены (MockEngine роутинг, @TempDir, чистая функция).

> **Note (flaky на Windows):** `@TempDir` cleanup на Windows может давать недетерминированный
> `IOException` при гонке файловой системы в параллельном прогоне (наблюдалось один раз в
> `WeatherStoreTest`). Не связано с кодом Day 19 — изолированный прогон или `--rerun-tasks` проходит clean.
