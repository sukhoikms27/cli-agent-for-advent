# Демо-сценарий: cross-server orchestration

> Длинный флоу взаимодействия с инструментами **с разных MCP-серверов** в одном `runToolLoop`.
> LLM сама выбирает и маршрутизирует — агент не хардкодит последовательность (как Day 19).

## Предусловия

1. `config.json` с двумя серверами (см. [`06-second-server.md`](./06-second-server.md)):
   - `local` (cli-agent-mcp) — 11 tools: `get_repo`, `search_wikipedia`, `format_report`,
     `save_to_file`, weather-tools, ...
   - `filesystem` (npx `@modelcontextprotocol/server-filesystem`) — 8 tools: `read_file`,
     `write_file`, `list_directory`, ...
2. `CLI_AGENT_API_KEY` задан (z.ai LLM).
3. Node.js + npx установлены (для filesystem-сервера).

## Сценарий: «Tech-дайджест с локальным файлом»

Пользователь:
```
cli-agent> Собери tech-дайджест по Kotlin: найди статью в Wikipedia, прочитай локальный README.md,
           сделай сводный отчёт и сохрани в notes
```

LLM сама оркестрирует цепочку (без хардкода в агенте):

| # | Tool | Сервер | Этап | Что делает |
|---|---|---|---|---|
| 1 | `search_wikipedia("Kotlin")` | **local** | search | Wikipedia REST → extract, url |
| 2 | `read_file("README.md")` | **filesystem** | search | локальный файл → содержимое |
| 3 | `format_report("Kotlin Digest", <wiki+readme>)` | **local** | process | markdown-отчёт |
| 4 | `save_to_file("kotlin-digest", <отчёт>)` | **local** | save | notes/kotlin-digest.md |

**Ключевое:** шаг 2 (`read_file`) идёт на **другой сервер** (`filesystem`), чем шаги 1/3/4 (`local`).
`CompositeMcpToolExecutor` прозрачно маршрутизирует — LLM не знает о разделении, видит единый
пул tools. `maxToolRounds=8` даёт запас для цепочки из 4 шагов + возможные повторы/уточнения.

### Ожидаемый вывод REPL

```
cli-agent> Собери tech-дайджест по Kotlin: найди статью в Wikipedia, прочитай локальный README.md,
           сделай сводный отчёт и сохрани в notes
🔧 Tool call: search_wikipedia({"query":"Kotlin"})
🔧 Tool call: read_file({"path":"README.md"})
🔧 Tool call: format_report({"title":"Kotlin Digest", ...})
🔧 Tool call: save_to_file({"slug":"kotlin-digest", ...})

Готово! Tech-дайджест по Kotlin сохранён в notes/kotlin-digest.md.
Отчёт включает выжимку из Wikipedia и ключевые секции README.md.
```

## Проверка критериев задания

| Критерий | Как проверяется |
|---|---|
| «агент выбирал нужный инструмент» | LLM сама выбрала 4 tool'а из пула 19 (11 local + 8 filesystem) |
| «корректно маршрутизировал запросы» | `read_file` → filesystem, остальные → local (routing-таблица) |
| «выполнял длинный флоу» | 4 шага в одном `runToolLoop` (≤ maxToolRounds=8) |
| «инструменты с разных серверов» | local (3 вызова) + filesystem (1 вызов) в одном потоке |
| «корректность выбора и порядка» | search → read → format → save (LLM выстроила логичный порядок) |

## Degradation-сценарий (упавший сервер)

Если `filesystem`-сервер недоступен (npx не установлен / нет сети):
```
cli-agent> /mcp
🔌 MCP servers (2):
  ✓ local       — 11 tools
  ✗ filesystem  — connect failed (npx not found)
⚠️ MCP server 'filesystem' unavailable: ...; skipping its tools.

cli-agent> Собери tech-дайджест по Kotlin и сохрани в notes
🔧 Tool call: search_wikipedia("Kotlin")     → [local]
🔧 Tool call: format_report(...)             → [local]
🔧 Tool call: save_to_file(...)              → [local]
→ Готово (без локального файла — filesystem пропущен, но агент НЕ упал)
```

**Graceful degradation:** упавший сервер skip'ается, остальные работают, LLM адаптируется
(может выполнить задачу частично). Соответствует философии `loadToolsOrNull` в агенте.
