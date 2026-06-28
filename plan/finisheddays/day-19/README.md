# День 19 — Композиция MCP-инструментов (README / общий план)

> Пайплайн `search → process → save`, который **LLM оркестрирует сама** (без хардкода в агенте).
> Сценарий «Tech-дайджест по теме»: Wikipedia + GitHub → Markdown-отчёт → файл. Корневой контекст:
> [`00-task.md`](./00-task.md).

## Что произошло с архитектурой

День 19 — это **минимальное, точечное расширение** Day 18. Ключевое архитектурное открытие:
клиентский модуль `src/` **не трогался ни строкой** — `ContextAwareAgent.runToolLoop`
(`MAX_TOOL_ROUNDS=4`) и `McpToolExecutor` из Day 17 уже умели чейнлить tools. Новые MCP-tools
«авто-подхватываются» через `McpTool.toToolDefinition()`. Вся работа — на серверной стороне (`:mcp-server`).

Единственное клиентское изменение (по запросу) — **лог вызова tool'а** в `runToolLoop`: `🔧 Tool call: <name>(args)`.

## Карта файлов

| # | Файл | Тип | Содержание |
|---|---|---|---|
| 00 | [`00-task.md`](./00-task.md) | контекст | Задание курса, уточнения куратора в чате, scope in/out, развилки, маппинг «задание→реализация» |
| — | [`README.md`](./README.md) | индекс/план | Этот файл: навигация, сценарий, итоги, структура модуля, env |
| 01 | [`01-wikipedia-client.md`](./01-wikipedia-client.md) | реализация | `WikipediaClient` (Ktor, opensearch→summary, без ключа, allowlist path-injection guard) |
| 02 | [`02-notes-store.md`](./02-notes-store.md) | реализация | `NotesStore` (atomic write temp+rename, slugify, sandboxed write, XDG `notes/`) |
| 03 | [`03-tools-registration.md`](./03-tools-registration.md) | реализация | 4 новых tools (`search_wikipedia`/`format_report`/`save_to_file`/`list_notes`) + DataPaths + Factory wiring |
| 04 | [`04-agent-tool-logging.md`](./04-agent-tool-logging.md) | реализация | Лог вызова tool'а в `ContextAwareAgent.runToolLoop` (единственное клиентское изменение) |
| 05 | [`05-tests.md`](./05-tests.md) | тесты | `WikipediaClientTest` + `NotesStoreTest` + `ReportFormatTest` — паттерны Day 18 (MockEngine/@TempDir/чистая ф-я) |
| 06 | [`06-verification.md`](./06-verification.md) | верификация | `./gradlew build` green, `tools/list`=11, 0 регрессий Day 17/18 |
| 07 | [`07-demo-scenario.md`](./07-demo-scenario.md) | демо | REPL-сценарий «tech-дайджест по Kotlin»: LLM сама вызывает цепочку 4 tools → файл |

## Пайплайн-сценарий (что делает Day 19)

Пользователь: **«Собери tech-дайджест по Kotlin и сохрани в файл»**

LLM сама оркестрирует цепочку (без хардкода в агенте):

| # | Tool | Этап | Источник |
|---|---|---|---|
| 1 | `search_wikipedia("Kotlin")` | **search** | Wikipedia REST (без ключа) → extract, url |
| 2 | `get_repo("JetBrains", "kotlin")` | **search** | GitHub API (reuse Day 17) → звёзды, описание |
| 3 | `format_report("Kotlin Digest", <wiki+repo>)` | **process** | детерминированный markdown-шаблон |
| 4 | `save_to_file("kotlin-digest", <отчёт>)` | **save** | notes/ + atomic write |

**Передача данных между tools:** LLM читает текстовые ответы шагов 1–2 и передаёт их как аргументы
в `format_report`. Это и есть «корректность передачи данных между инструментами» из задания.

> `format_report` — **детерминированная** обработка (не LLM-суммаризация, по уточнению куратора).
> MCP-сервер остаётся **независимым от LLM**.

## Итоги

- **Было (Day 17–18):** 7 tools (`get_repo` + 6 погодных).
- **Стало (Day 19):** **11 tools** (+ `search_wikipedia`, `format_report`, `save_to_file`, `list_notes`).
- **Новый код:** ~3 файла main + 3 файла test + 2 точечные правки (DataPaths, Factory) + 1 правка клиента (лог).
- **Клиентский модуль `src/`:** одно изменение — лог tool-call'а (по запросу). runToolLoop/McpToolExecutor — без изменений.

## Структура модуля (после Day 19)

```
mcp-server/src/main/kotlin/com/cliagent/mcp/server/
├── McpServerApp.kt          # main() + transport — wiring новых singleton'ов
├── McpServerFactory.kt      # buildServer(...) — регистрирует 4 новых tools
├── util/
│   ├── Args.kt              # helpers (без изменений)
│   └── DataPaths.kt         # +notesDir — 1 строка
├── tools/
│   ├── GitHubTools.kt       # get_repo (Day 17, без изменений)
│   ├── WeatherTools.kt      # 6 погодных (Day 18, без изменений)
│   ├── WikipediaTools.kt    # search_wikipedia — NEW (Day 19)
│   └── NotesTools.kt        # format_report + save_to_file + list_notes — NEW (Day 19)
├── wikipedia/               # NEW (Day 19)
│   └── WikipediaClient.kt   # Ktor opensearch→summary, без ключа
├── notes/                   # NEW (Day 19)
│   └── NotesStore.kt        # atomic write, slugify, sandboxed
└── weather/                 # (Day 18, без изменений)
```

## Конвенции кода (AGENTS.md, соблюдены во всех задачах)

- **Persistence:** JSON/markdown, **atomic write** (temp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`), XDG-пути.
- **Безопасность (Week 04):** read-only источники (Wikipedia, GitHub); `save_to_file` — sandboxed write
  (только `notes/`, slugify filename, path-injection guard `../`→safe).
- **Валидация входов** до подстановки в URL/имя файла (allowlist: `QUERY_REGEX`, `slugify`).
- **Единый Json:** `Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }`.
- **Coroutines:** `CancellationException` **никогда не глотать** — всегда re-throw перед generic `catch`.
- **stdio-режим:** stdout несёт **только JSON-RPC**; логи → stderr.
- **Tool-ошибки** → `CallToolResult(isError=true)`, **не** exception — видны LLM для самокоррекции.

## Справочник env-переменных

### Существующие (Day 17–18, не меняются)
| Env | Назначение |
|---|---|
| `CLI_AGENT_MCP_MODE` | `http` \| `stdio` — выбор транспорта сервера |
| `CLI_AGENT_MCP_TOKEN` | bearer для auth (http-режим) / клиентского подключения |
| `CLI_AGENT_GITHUB_TOKEN` | PAT для `get_repo` |
| `CLI_AGENT_API_KEY` | z.ai (LLM, на клиенте) |

### Day 19 — **новых env НЕТ**
> Wikipedia REST и NotesStore работают **без ключей** и без конфигурации. Сервер стартует и
> инструменты доступны сразу. Путь notes: `$XDG_DATA_HOME/cli-agent/notes/` (default `~/.local/share/cli-agent/notes/`).

## Чек-лист приёмки (соответствие заданию курса)

- [x] «первый инструмент получает данные» — `search_wikipedia` / `get_repo` (search-этап)
- [x] «второй — обрабатывает» — `format_report` (process-этап, детерминированный отчёт)
- [x] «третий — сохраняет результат» — `save_to_file` (save-этап, atomic write)
- [x] «автоматическое выполнение цепочки» — LLM сама вызывает 4 tools через `runToolLoop` (без хардкода)
- [x] «корректность передачи данных» — ответы search→аргументы format_report→аргументы save_to_file
- [x] «3 любых tools, LLM вызывает цепочку» — по уточнению куратора в чате (см. [`00-task.md`](./00-task.md))
- [x] **0 регрессий** Day 17 (`get_repo`) и Day 18 (6 погодных) — `tools/list`=11, все тесты green
