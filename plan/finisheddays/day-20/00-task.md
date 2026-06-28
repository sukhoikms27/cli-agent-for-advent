# 00 — Контекст: задание Day 20

## Задание курса

> **🔥 День 20. Orchestration MCP**
>
> Зарегистрируйте несколько MCP-серверов.
>
> Сделайте так, чтобы:
> - 👉 агент выбирал нужный инструмент
> - 👉 корректно маршрутизировал запросы
> - 👉 выполнял длинный флоу взаимодействия
>
> Проверьте:
> - 👉 сценарий, в котором используются инструменты с разных серверов
> - 👉 корректность выбора и порядка вызовов
>
> **Результат:** Длинный флоу взаимодействия с несколькими MCP-серверами и инструментами.

Источник: `plan/newdays/day20.md`.

## Контекст предыдущих дней

- **Day 16** — MCP-клиент (stdio), `tools/list` без вызова tools.
- **Day 17** — собственный MCP-сервер над GitHub REST (`get_repo`), полный LLM function-calling
  цикл в агенте (`ContextAwareAgent.runToolLoop`, `MAX_TOOL_ROUNDS=4`), `McpToolExecutor`.
- **Day 18** — remote Streamable HTTP, weather-сервер (6 tools, scheduler).
- **Day 19** — композиция tools на **одном** сервере (search→process→save, +4 tools = 11 total).
  Куратор: «не надо делать 3 разных mcp» — несколько tools на одном сервере. Несколько серверов — Day 20.

**Ключевое ограничение до Day 20:** клиент (`McpToolExecutor`/`ContextAwareAgent`) работал с
**одним** сервером. Multi-server orchestration отсутствовал.

## Принятые решения пользователя (развилки)

| Развилка | Решение | Обоснование |
|---|---|---|
| **Р1. Server #2** | Public npx `@modelcontextprotocol/server-filesystem` | Уже в Day 16, минимум кода, тема лекции про public tools |
| **Р2. Namespacing** | prefix-on-collision (`server__tool` только при коллизии) | 0 регрессий single-server; raw имена иначе |
| **Р3. Миграция legacy** | Явная команда `/config init` | Безопаснее авто-генерации; пользователь контролирует |
| **Формат конфига** | Единый `config.json` с массивом `mcp` | Масштабируемо (как Claude Code), одна точка конфигурации |
| **maxToolRounds** | Конфигурируемый, default **8** | «Длинный флоу» cross-server chaining (search→read→format→save) |
| **Добавление сервера** | Пути A (ручное ред. config.json) + B (`/mcp add`) | declarative + convenience без env-массива |

## Маппинг «задание → реализация»

| Требование задания | Реализация | Файл |
|---|---|---|
| «зарегистрируйте несколько MCP-серверов» | `config.json` массив `mcp` + `/mcp add` | `ConfigRepository`, `McpServerConfig` |
| «агент выбирал нужный инструмент» | `CompositeMcpToolExecutor.definitions()` — merge tools | `CompositeMcpToolExecutor` |
| «корректно маршрутизировал запросы» | routing-таблица `toolName → server` | `CompositeMcpToolExecutor.call()` |
| «выполнял длинный флоу» | `maxToolRounds` (default 8) + cross-server chaining | `ContextAwareAgent`, `config.json` |
| «инструменты с разных серверов» | demo: search_wikipedia [local] → read_file [filesystem] → ... | `06-demo-scenario.md` |

## Что отнесено за пределы Day 20

- **Skill+CLI-инфраструктура** — отсутствует в коде, не требуется заданием. Идея «MCP vs Skill+CLI»
  из `global-plan.md` была самопланированием до получения задания — снята как устаревшая.
- **Token-flow benchmark** — не часть официального задания (тема недели, но не Day 20).
- **MCP resources/prompts** — только tools (как Day 16–19).
- **Remote TLS/auth** для public-серверов — вне демо (read-only public servers).
