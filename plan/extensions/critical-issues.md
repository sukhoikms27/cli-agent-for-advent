# Critical Issues — cli-agent

Кураторский обзор самых серьёзных проблем проекта, выявленных роем из трёх агентов
(архитектурный, ООП, баг-хантер) и **перепроверенных вручную по исходникам**.
Каждая находка подтверждена `file:line`. Подробности по категориям — в
[`arch-issues.md`](arch-issues.md), [`oop-issues.md`](oop-issues.md), [`bugs.md`](bugs.md).

> Метод проверки: ast-index (поиск/usage/callers) + прямое чтение файлов. Гипотезы,
> не подтверждённые кодом, сюда не попали.

---

## 🔴 Критические (ломают пользовательский функционал)

### 1. Команда `/strategy` не переключает стратегию — метод `switchStrategy` ничего не сохраняет
- **Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:249-259`
- **Что не так:** `switchStrategy(newManager)` загружает state для новой стратегии и
  возвращает `"Switched to $msg"`, но **не присваивает `newManager` полю `contextManager`**.
  Поле `contextManager` объявлено `private val` (строка 42) — переприсвоить нельзя физически.
- **Сценарий:** пользователь вводит `/strategy facts` → `ChatCommand.handleStrategy`
  (`ChatCommand.kt:522-524`) вызывает `agent.switchStrategy(newManager)`, видит
  «Switched to facts», но агент продолжает использовать стартовую стратегию.
  Весь runtime-переключатель контекстных стратегий неработоспособен.
- **Подтверждение:** чтение `ContextAwareAgent.kt:42,249-259` + caller `ChatCommand.kt:523`.
- **Детали:** [`oop-issues.md`](oop-issues.md) §switchStrategy, [`bugs.md`](bugs.md).

### 2. Команда `/branch switch <name>` всегда падает — switch ищет по id, а передаётся name
- **Файл:** `src/main/kotlin/com/cliagent/context/strategy/BranchingStrategy.kt:48-55`
- **Что не так:** `switchBranch(branchId)` проверяет `branchId !in branches`, но `branches`
  индексированы по `id` (`BranchingStrategy.kt:19` → `branches[it.id] = it`). При этом
  `createBranch` генерирует `id = "branch-${UUID.randomUUID()}"` (`JsonChatStore.kt:188`),
  а caller передаёт **имя** ветки (`ChatCommand.kt:566-568`: `val name = parts[2]`).
- **Сценарий:** `/branch create mybranch` → создаётся с id `branch-<uuid>`.
  `/branch switch mybranch` → `"mybranch" !in branches` (там ключ `branch-<uuid>`) →
  `Result.failure` → «Branch 'mybranch' not found». Помог бы только `/branch switch branch-<uuid>`,
  но help (`ChatCommand.kt:318`) обещает `<name>`.
- **Подтверждение:** чтение `BranchingStrategy.kt`, `JsonChatStore.kt:185-201`, `ChatCommand.kt:561-574`.

### 3. `CancellationException` проглатывается в LLM-клиенте
- **Файл:** `src/main/kotlin/com/cliagent/llm/OpenAiCompatibleClient.kt:52, 68`
- **Что не так:** `catch (e: Exception)` ловит `kotlinx.coroutines.CancellationException`
  (он подтип `Exception`) и превращает его в `LlmResult.Error`. Нарушен корутин-контракт:
  `CancellationException` обязан пробрасываться.
- **Сценарий:** отмена долгого LLM-запроса / shutdown REPL / будущий `withTimeout` — корутина
  не отменяется, продолжает работу, возвращает `LlmResult.Error` →上层 бросает `LlmCallException`.
- **Подтверждение:** чтение `OpenAiCompatibleClient.kt:39-71`.

---

## 🟠 Высокие (деградация/утечки)

### 4. Утечка ресурсов: `HttpClient` и `JsonChatStore` не закрываются при выходе из REPL
- **Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:80-204`
- **Что не так:** `client = OpenAiCompatibleClient(...)` (держит `HttpClient(CIO)` с пулом соединений)
  и `memoryStore = JsonChatStore()` (`AutoCloseable`, writer-актор + `SupervisorJob`). REPL выходит
  через `break` (строки 165/169) или `return@runBlocking` (87) — **без `finally`/`.close()`**.
- **Сценарий:** каждый выход через `/exit`/Ctrl+D оставляет висеть CIO-пулы (треды/сокеты) и
  writer-корутину. Pending-записи в `Channel.UNLIMITED` могут не дренироваться → потеря данных
  при выходе сразу после отправки сообщения.
- **Подтверждение:** чтение `ChatCommand.kt:80-204`; `JsonChatStore` реализует `AutoCloseable`.

### 5. `choices.first()` без защиты — `NoSuchElementException` на пустом ответе
- **Файлы:** `agent/ContextAwareAgent.kt:120`, `context/HistoryCompressor.kt:87,117`,
  `agent/ProfileExtractor.kt:56`, `agent/SimpleAgent.kt:35`, `llm/BenchmarkRunner.kt:37`
- **Что не так:** `ChatResponse.choices` — `List<Choice>` **без дефолта**
  (`llm/model/ChatResponse.kt:9`). При пустом массиве `choices` (content-filter, ошибки в теле)
  `first()` бросает `NoSuchElementException`, который в `ContextAwareAgent.chat` **не ловится**
  (там ловят только `LlmCallException`) → роняет весь чат.
- **Подтверждение:** чтение `ChatResponse.kt:6-11`, `ContextAwareAgent.kt:120`. В классификаторах
  stages используется безопасный `firstOrNull()` — асимметрия подтверждает пропуск.

### 6. `ChatCommand` — god-объект (1118 строк, ~30 методов, 11 ответственностей)
- **Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:44-1118`
- **Что не так:** один класс совмещает wiring зависимостей, REPL-цикл, диспетчеризацию 14+
  slash-команд, рендер таблиц/справки, парсинг и бизнес-логику memory/profile/invariants/task,
  фабрику стратегий, обработку LLM-исключений. 32 cross-layer импорта.
- **Подтверждение:** чтение файла; `CliAgentCommand` — пустая заглушка, заявленного в CLAUDE.md
  `ConfigCommand.kt` не существует.
- **Детали:** [`oop-issues.md`](oop-issues.md) §1, [`arch-issues.md`](arch-issues.md).

---

## 🟡 Средние (архитектурные разрывы)

### 7. Циклическая зависимость между слоями memory ↔ state ↔ llm
- **memory → state:** `memory/MemoryLayer.kt:3-5` импортирует `InteractionMode`, `TaskState`, `Invariant`.
- **state → llm:** `state/invariant/LlmInvariantChecker.kt:3-6` импортирует `LlmClient`, `LlmResult`, `ChatMessage`, `ChatRequest`.
- **llm → state:** `llm/model/StagePromptTemplates.kt:3-4` импортирует `TaskKind`, `TaskStage`.
- **Итог:** цикл `state → llm → state` + двусторонняя связь `memory ↔ state`. `global-plan.md`
  заявляет однонаправленные слои с `state/` и `memory/` без нижних зависимостей.
- **Подтверждение:** `grep import` по пакетам (см. [`arch-issues.md`](arch-issues.md)).

### 8. Доменный слой (agent) пишет в stdout через сырой `println`
- **Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:91, 96, 105`
- **Что не так:** агент напрямую `println("🔄 Compressing history...")` и т.п. CLAUDE.md
  предписывает вывод через mordant (`AppTerminal`), данные → stdout, ошибки → stderr.
- **Подтверждение:** 3 попадания `println` в `agent/` (остальные agent-классы чистые).

### 9. Разрыв документации и кода: `AgentResult`/`ExitCodes` заявлены, но отсутствуют
- **CLAUDE.md** детально описывает `sealed class AgentResult<out T>` и `object ExitCodes` (POSIX).
- **В коде:** 0 деклараций (`grep -rn "AgentResult|ExitCodes" src/main/` → только комментарий в
  `InvariantResult.kt:11`). Ошибки LLM бросаются как `LlmCallException` — т.е. **исключения
  используются для flow control**, что прямо противоречит конвенции CLAUDE.md
  («no exceptions for flow control»).
- **Подтверждение:** grep + чтение `ContextAwareAgent.kt:124,163`, `ChatCommand.kt:270`.

---

## Мёртвый код (заявлен, не используется)

| Элемент | Файл | Подтверждение |
|---|---|---|
| `SimpleAgent` | `agent/SimpleAgent.kt:11` | `grep SimpleAgent src/` → только декларация, 0 usages. Заменён `ContextAwareAgent`. |
| `BenchmarkRunner` | `llm/BenchmarkRunner.kt:10` | 0 usages, нет `/benchmark` команды. |
| `ContextStrategyType` enum | `context/strategy/ContextStrategy.kt:5` | 0 usages (`ast-index usages` → 0); 2 мёртвых импорта. `global-plan.md` явно его отвергает. |
| `SummaryStrategy.clearSummaryIfRequested()` | `context/strategy/SummaryStrategy.kt:35` | 0 callers; latent-баг — `reset()` выставляет флаг, но очистка никогда не сработает. |

Полные списки — в [`arch-issues.md`](arch-issues.md) и [`bugs.md`](bugs.md).
