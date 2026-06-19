# Дизайн: Файловые операции для CLI-агента

**Дата:** 2026-06-19
**Статус:** Draft (ожидает review)
**Тип:** Самостоятельное расширение (вне курса курса; курс откладывает tool orchestration до «MCP недели»)

## Контекст и мотивация

CLI-агент сегодня строго text-in/text-out: `Agent.chat(userMessage): String` возвращает строку,
никаких side effects. Стадия `execution` авто-флоу day-13 только *описывает* реализацию текстом
в `TaskState.implementation`, но не пишет файлы.

Цель: дать агенту возможность **автономно** создавать/читать файлы и осматривать каталоги в ходе
работы — чтобы, например, на стадии execution он реально записывал сгенерированный код в проект.

## Решения (по ответам пользователя)

1. **Триггер — автономный.** Агент сам решает, когда выполнять файл-операции (не по явной
   команде пользователя и не только на стадии execution).
2. **Механизм — промпт-протокол с заделом под нативный tool API.** Модель эмитит fenced-блоки
   `cli-tool:<name>`; агент парсит и исполняет. `ActionRegistry`/`ActionHandler` — абстракция,
   которая переживёт смену транспорта на нативный OpenAI tool-calling позже.
3. **Sandbox — каталог проекта (cwd).** Запись/чтение только внутри `rootDir` и его подпапках.
4. **Операции MVP:** `write_file` (создать/перезаписать), `read_file`, `list_dir`.
   Деструктивные (delete/move) — **out of scope**.
5. **Подтверждение — только для `write_file`** (меняет ФС). `read_file`/`list_dir` выполняются
   сразу с отчётом. Opt-out через `--no-confirm`.

## Архитектура

### Подход: Decorator `ActingAgent` поверх `ContextAwareAgent`

Новый `ActingAgent(delegated: Agent, registry: ActionRegistry) : Agent` реализует тот же интерфейс
`Agent`. В `chat()`:

1. `delegated.chat(userMessage)` — `ContextAwareAgent` (stage-промпт, история, профиль, profile).
2. `ActionBlockParser.parse(response)` — ищет блоки ` ```cli-tool:<name>\n{json} ``` `.
3. Блоков нет → вернуть ответ (обычный чат, нулевые накладные).
4. Блоки есть → для каждого:
   - `registry.execute(name, argsJson)` → sandbox-проверка → (write_file) `confirm()` → исполнение.
   - `ActionResult` (success/error текстом).
5. Собрать результаты → `delegated.chat("Результаты операций:\n...")` → повтор (итерация++).
6. **Цикл с ограничением max 5 итераций** (защита от бесконечного цикла); при достижении лимита —
   вернуть последний ответ + `⚠️ достигнут лимит итераций (5)`.

**Почему decorator:** Open-Closed — `ContextAwareAgent` и его 61 существующий тест не трогаются;
sandbox, подтверждение и протокол изолированы в одном классе; легко тестировать (mock делегата).

**Единственная правка ContextAwareAgent:** необязательный параметр
`systemPromptAppendix: String? = null` — ActingAgent подмешивает сюда промпт-протокол, когда `--act`.
Default `null` → нулевая регрессия.

### Интеграция в ChatCommand

```kotlin
val base = ContextAwareAgent(..., systemPromptAppendix = if (act) ActionPrompts.block else null)
val agent = if (act) ActingAgent(base, ActionRegistry.default(Path("."))) else base
```
Блок `else` REPL **не меняется** — он уже зовёт `agent.chat()`. Поведение без `--act` = сейчас.

### Флаги

- `--act` (env `CLI_AGENT_ACT`): включает промпт-протокол и обработку блоков. **Default off**
  (обратная совместимость).
- `--no-confirm`: отключает y/n для `write_file` (dev/CI). Default off.
- `/help`: новая строка про файл-операции.

## Компоненты (новый пакет `agent/action/`)

| Компонент | Ответственность |
|---|---|
| `AgentAction` (sealed) | `WriteFile(path, content, overwrite)`, `ReadFile(path)`, `ListDir(path)` — типобезопасные DTO. |
| `ActionResult` (sealed) | `Success(message)`, `Error(path, message)` — маппится на `AgentResult.IoError` (CLAUDE.md). |
| `ActionRegistry` | Реестр `name → ActionHandler`; `execute(name, argsJson, rootDir): ActionResult`. |
| `ActionHandler` (interface) | `parse(args: JsonObject): AgentAction`, `execute(action, rootDir): ActionResult`. |
| `WriteFileHandler` / `ReadFileHandler` / `ListDirHandler` | `atomicWrite` (temp+rename), `withContext(Dispatchers.IO)`. Sandbox в каждом. |
| `SandboxGuard` | `validate(path, rootDir): ActionResult?` — `toRealPath()`+`startsWith(root)`; `ensureDir()` для родительских. |
| `ActionBlockParser` | Чистая функция: fenced `cli-tool:<name>` блоки → `List<ActionBlock(name, json)>` + чистый текст. |
| `ActionPrompts` | Текст промпт-протокола для system prompt. |

## Промпт-протокол

Модель инструктируется эмитить блоки:
```
Чтобы создать/прочитать файл или осмотреть каталог, вставь блок:
\`\`\`cli-tool:write_file
{"path":"src/Main.kt","content":"..."}
\`\`\`
Доступные: write_file, read_file, list_dir. Пути — относительно корня проекта.
```
`ActingAgent` добавляет этот блок в system-prompt делегата (через `systemPromptAppendix`).

## Sandbox

`rootDir` = `Path(".")` (cwd). `SandboxGuard` валидирует каждый путь:
- `Path.toRealPath()` (развёртывает symlinks/`..`) + `startsWith(root.toRealPath())`.
- Выход за пределы → `ActionResult.Error`, операция не выполняется.
- Для `write_file` — `Files.createDirectories(parent)` перед записью (как `ensureDir()` в `JsonChatStore`).

## Цикл (tool-call loop)

```
msg → delegated.chat(msg) → parse
  нет блоков  → вернуть ответ
  есть блоки  → execute (sandbox + confirm для write) → собранные ActionResult
              → delegated.chat("Результаты:\n...") → повтор (iter++)
  iter >= 5   → вернуть + ⚠️ лимит
```

**Задел под нативный tool API:** `ActionRegistry` + `ActionHandler` переживают смену транспорта.
Позже (MCP-неделя): `tools` в `ChatRequest`, `tool_calls` в ответе, второй парсер в `ActingAgent`
(нативный вместо промпт-блоков); registry/handlers без изменений.

## Обработка ошибок (конвенции CLAUDE.md)

- `ActionResult.Error(path, message)` — для ошибок ФС (sandbox violation, файл не найден, I/O).
- `LlmResult.Error` от делегата — пробрасывается как сейчас (текст `Error: ...`).
- `CancellationException` — никогда не глотать, пробросить.
- Корутины: `withContext(Dispatchers.IO)` для файловых операций.

## Тестирование

JUnit 5 + MockK + `runTest` + `@TempDir` (конвенция проекта):
- `ActionBlockParserTest`: парсинг, множественные блоки, вложенные не-tool fenced-блоки, код с
  обратными кавычками внутри, мусор/отсутствие блоков.
- `SandboxGuardTest`: внутри root (ok), `../escape` (block), абсолютный вне root (block),
  symlink escape (block), создание родителей.
- `WriteFileHandlerTest` / `ReadFileHandlerTest` / `ListDirHandlerTest`: `@TempDir`, atomic write,
  read non-existent → error, list recursive, overwrite.
- `ActingAgentTest`: mock делегата (coEvery с/без блоков) → execute/confirm/цикл/max-iter;
  интеграция с реальным `ContextAwareAgent` + mock `LlmClient` (как в day-13).
- `confirm`: тестируется через injectable `confirm: (String)->Boolean` (в проде — REPL, в тесте — stub).

## Вне скоупа (явно)

- Деструктивные операции (delete_file, move/rename).
- Нативный OpenAI tool-calling (задел под MCP-неделю, не сейчас).
- Редактирование по diff/patch (только полный overwrite в MVP).
- Параллельные файл-операции.

## Критерии приёмки

- [ ] Агент автономно создаёт файл (`write_file`) в каталоге проекта после подтверждения y/n.
- [ ] Агент читает файл (`read_file`) и осматривает каталог (`list_dir`) без подтверждения.
- [ ] Sandbox блокирует запись вне каталога проекта (`../escape`, абсолютные пути).
- [ ] Tool-call loop: результаты операций подмешиваются в контекст, агент продолжает (max 5 итераций).
- [ ] Без `--act` поведение идентично текущему (нулевая регрессия, 61 тест зелёный).
- [ ] `--no-confirm` отключает y/n для write_file.
- [ ] `./gradlew test build` зелёный.

## Зависимости и риски

- **Зависимости:** `ContextAwareAgent` (новый optional параметр), `ChatCommand` (флаги + wiring),
  `ReplEngine` (completer для `/help`), I/O-паттерны `JsonChatStore`/`JsonLongTermStore` (`atomicWrite`).
- **Риск 1 — хрупкость парсинга:** модель может эмитить блоки в нестандартном виде. Митигация:
  толерантный парсер + fallback (неразобранный блок → `ActionResult.Error`, агент просит переформулировать).
- **Риск 2 — бесконечный цикл:** модель постоянно эмитит блоки. Митигация: max 5 итераций.
- **Риск 3 — подтверждение в неинтерактивном режиме:** при `-c`/pipe ввода нет. Митигация: в
  неинтерактивном режиме подтверждение = отклонение (или `--no-confirm` обязателен).
- **Риск 4 — Windows пути:** проект на win32; `Path.toRealPath()` и `Files.move(ATOMIC_MOVE)`
  должны корректно работать на Windows. Проверить в тестах.
