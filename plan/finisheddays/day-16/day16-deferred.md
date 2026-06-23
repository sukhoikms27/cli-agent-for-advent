# День 16 — Отложенные до рефакторинга проблемы

Эти элементы не требуются для deliverable дня 16 (connect + listTools) и переносятся на будущие
этапы. День 16 реализован в минимально достаточном виде.

## 1. God-объект `ChatCommand` (1118 строк)
День 16 добавил `handleMcp` (~60 строк) как `internal suspend` в существующем паттерне `handle*`.
Это усугубляет god-объект (см. `oop-issues.md` §1, `critical-issues.md` §6).

**Рефакторинг (отдельный день):** вынос `handle*`/`print*` в handler-классы по группам команд —
`McpCommandHandler`, `TaskCommandHandler`, `MemoryCommandHandler`, `ProfileCommandHandler`,
`InvariantsCommandHandler`, `BranchCommandHandler`, `StrategyCommandHandler`. В `ChatCommand`
остаются только флаги+wiring+REPL-цикл+диспетчеризация `when`. REPL `when` (19 веток) при этом
становится таблицей `command → handler.handle(...)`.

## 2. Shell-токенизатор для `/mcp list-tools`
Сейчас `split("\\s+")` — не поддерживает пути с пробелами/кавычками
(`/Users/.../My Documents/server` разобьётся на токены → кривой argv).

**Рефакторинг (день 17):** корректный shell-парсер с учётом кавычек
(`Regex("""[^\s"]+|"([^"]*)"""")`), применяемый единообразно к override-команде и к `mcp.command`
из конфига.

## 3. Persistent MCP-соединение
День 16 поднимает MCP-сервер (subprocess) на каждый `/mcp list-tools` — cold-start `npx` ~2-5с.

**Рефакторинг (день 17, с `callTool`):** persistent `McpClient` как поле `ChatCommand` с lifecycle
(создание при первом использовании, `close()` в `finally` выхода из REPL — заодно починит
существующий пробел с незакрытыми `client`/`memoryStore`, см. `bugs.md` §4). Reconnect при падении
сервера.

## 4. Полный рендер `inputSchema`
День 16 хранит `inputSchema` как JSON-строку (`McpTool.inputSchema: String?`), не отображает в таблице.

**Рефакторинг (день 17):** читаемый вывод схемы аргументов (таблица параметров: name/type/required),
валидация аргументов перед `callTool`.

## 5. Ссылки на ранее найденные проблемы проекта
Отдельная от дня 16 работа — см. корневые файлы репозитория:
- `critical-issues.md` — топ-проблем (включая `/strategy` не переключает, `/branch switch` сломан).
- `arch-issues.md` — архитектурные (циклы memory↔state↔llm, `println` в домене, мёртвый код).
- `oop-issues.md` — god-классы, ISP `MemoryStore`, `TaskStage` enum vs sealed.
- `bugs.md` — runtime-баги (`CancellationException` в `OpenAiCompatibleClient`, утечка ресурсов, и др.).
