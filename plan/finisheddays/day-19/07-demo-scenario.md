# 07 — Демо-сценарий (REPL)

> Сценарий «Tech-дайджест по Kotlin»: доказывает, что **LLM сама оркестрирует** цепочку 4 tools
> (без хардкода в агенте) с корректной передачей данных. Требует LLM API key + запущенного MCP-сервера.

## Подготовка окружения

```bash
# 1. Сервер (в одном терминале) — http-режим, как Day 18 VPS-деплой
export CLI_AGENT_MCP_MODE=http
export CLI_AGENT_MCP_PORT=8080
export CLI_AGENT_MCP_TOKEN=secret-token-123
export CLI_AGENT_GITHUB_TOKEN=<pat>           # для get_repo
./gradlew :mcp-server:shadowJar
java -jar mcp-server/build/libs/mcp-server-0.1.0-all.jar
# stderr: "[mcp] server started on 0.0.0.0:8080/mcp"

# 2. Клиент (в другом терминале) — подключение к серверу + LLM
export CLI_AGENT_MCP_URL=http://localhost:8080/mcp
export CLI_AGENT_MCP_TOKEN=secret-token-123   # bearer (как у сервера)
export CLI_AGENT_API_KEY=<z.ai-key>           # LLM
./gradlew installDist
./build/install/cli-agent/bin/cli-agent chat
```

> В stdio-режиме (default) сервер поднимается сам как subprocess клиента — тогда нужен только
> `CLI_AGENT_API_KEY` + `CLI_AGENT_MCP_COMMAND` (или отсутствие `CLI_AGENT_MCP_URL`). Ниже — для http.

## Сценарий A. Цепочка 4 tools — tech-дайджест (главная демонстрация)

```
cli-agent> Собери tech-дайджест по Kotlin: что это (из Wikipedia), звёзды репозитория
           JetBrains/kotlin, и сохрати всё одним отчётом в файл kotlin-digest.
```

**Ожидаемое поведение** — LLM вызывает цепочку (видна в логе агента):
```
🔧 Tool call: search_wikipedia(query="Kotlin", language="en")
🔧 Tool call: get_repo(owner="JetBrains", repo="kotlin")
🔧 Tool call: format_report(title="Kotlin Digest", sections=[2 items])
🔧 Tool call: save_to_file(filename="kotlin-digest", content="# Kotlin Digest...")
```

**Что доказывает:**
1. **Автоматическое выполнение цепочки** — агент не хардкодил последовательность; LLM сама решила
   вызвать 4 tools в порядке search→search→process→save (в рамках `MAX_TOOL_ROUNDS=4`).
2. **Корректность передачи данных** — ответы `search_wikipedia`/`get_repo` (текст) LLM прочитала и
   передала как аргументы `sections` в `format_report`; готовый markdown — как `content` в `save_to_file`.

**Проверка результата:**
```bash
cat $XDG_DATA_HOME/cli-agent/notes/kotlin-digest.md
# # Kotlin Digest
#
# _28.06.2026_
#
# ## Раздел 1
# (extract из Wikipedia про Kotlin...)
# ## Раздел 2
# JetBrains/kotlin | ⭐46000 | Kotlin | master | ...
```

## Сценарий B. Проверка персистентности — `list_notes`

```
cli-agent> Покажи, какие заметки у меня уже сохранены.
```
LLM вызывает `list_notes` → видит `kotlin-digest.md` (размер, дата). Доказывает, что `notes/`
пережила цепочку и доступна отдельным tool'ом.

## Сценарий C. Только search (без save) — LLM выбирает подмножество

```
cli-agent> Что пишет Wikipedia про микросервисы? Кратко.
```
LLM вызывает **только** `search_wikipedia` и отвечает текстом — **без** format/save. Доказывает,
чтоtools вызываются **по необходимости** (агент не гоняет весь пайплайн всегда) — это и есть
«оркестрация» в духе Day 19/20.

## Сценарий D. Передача данных через NotesStore (косвенный путь)

Альтернативный сценарий, где данные накапливаются через персистентное хранилище (как погода в Day 18):
```
cli-agent> Найди статью про Docker, оформи как отчёт и сохрани.
```
LLM: `search_wikipedia("Docker")` → `format_report(...)` → `save_to_file("docker-report", ...)`.
Те же звенья (search→process→save), но один источник — проще для дебага, если цепочка ломается.

## Чек-лист демо

- [ ] Сценарий A: цепочка из 4 tools видна в логе `🔧 Tool call: ...`.
- [ ] Файл `notes/kotlin-digest.md` создан с корректным markdown (заголовок, дата, 2 секции).
- [ ] Сценарий B: `list_notes` показывает сохранённый файл.
- [ ] Сценарий C: LLM вызывает только нужные tools (не гоняет пайплайн без необходимости).
- [ ] Никаких хардкод-последовательностей в `ContextAwareAgent` — только `runToolLoop` исполняет
      то, что просит LLM.

## Troubleshooting

| Симптом | Причина / фикс |
|---|---|
| LLM не вызывает tools, отвечает «я не могу» | `CLI_AGENT_MCP_URL`/`_TOKEN` не заданы → `McpToolExecutor` fallback в single-shot (агент логирует `⚠️ MCP tools unavailable`). Проверь подключение к серверу. |
| Цепочка обрывается на 4-м tool | `MAX_TOOL_ROUNDS=4`. Если LLM вызывает tools по одному (wiki, repo, format, save = 4 раунда), последний может не влезть. Решение: попросить LLM батчить wiki+repo в одном ответе, или поднять константу (Day 20). |
| `save_to_file` возвращает error | filename-sanitization: слишком «агрессивное» имя схлопывается в `note`. Проверь `notes/` — файл всё равно создастся под безопасным именем. |
| `search_wikipedia` → «не найдено» | Wikipedia opensearch не резолвит фразу. Попробуй точнее («Kotlin programming language» вместо «котлин»). |
