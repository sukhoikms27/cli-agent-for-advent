# Bugs & Minor Issues — cli-agent

Баги и недочёты, выявленные баг-хантером и **перепроверенные вручную** по исходникам.
Каждая находка помечена: ✅ подтверждено чтением кода / ⚠️ потенциальный (требует edge-case
или будущего изменения). Серьёзность: 🔴 высокая, 🟠 средняя, 🟡 низкая.

---

## 🔴 Высокие

### 1. ✅ `CancellationException` проглатывается в LLM-клиенте
**Файл:** `src/main/kotlin/com/cliagent/llm/OpenAiCompatibleClient.kt:52, 68`
```kotlin
} catch (e: Exception) {                              // :52 — внутренний (parse)
    System.err.println("[DEBUG] Raw API response:\n$bodyText")
    LlmResult.Error(0, "Failed to parse response: ${e.message}")
}
...
} catch (e: Exception) {                              // :68 — внешний (HTTP)
    LlmResult.Error(0, "Request failed: ${e.message}")
}
```
`Exception` — супертип `kotlinx.coroutines.CancellationException`. При отмене корутины
(shutdown REPL, будущий `withTimeout`) `CancellationException` превращается в `LlmResult.Error`
вместо проброса. Нарушен корутин-контракт; ломает cancellation propagation по стеку.
**Фикс:** `catch (e: CancellationException) { throw e }` перед `catch (e: Exception)`, либо
`catch (e: Exception) { if (e is CancellationException) throw e; ... }`.

### 2. ✅ `/branch switch <name>` всегда падает (NoSuchElement-ish / «not found»)
**Файл:** `src/main/kotlin/com/cliagent/context/strategy/BranchingStrategy.kt:48-55`
`switchBranch(branchId)` ищет `branchId in branches`, но `branches` keyed by **id**
(`:19`), а caller передаёт **имя** (`ChatCommand.kt:568`). `createBranch` генерирует
`id = "branch-<uuid>"` (`JsonChatStore.kt:188`) — id ≠ name. `/branch switch mybranch` →
`"mybranch" !in branches` → `Result.failure` → «Branch 'mybranch' not found». Команда
неработоспособна; help обещает `<name>`.
**Фикс:** искать по name (`branches.values.firstOrNull { it.name == branchId }`), либо
принимать id и менять help.

### 3. ✅ `/strategy` не переключает стратегию (метод-пустышка)
**Файл:** `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt:249-259`
`switchStrategy(newManager)` не присваивает `newManager` полю `contextManager` (`private val`,
:42). Пользователь видит «Switched to facts», но стратегия остаётся стартовой.
**Фикс:** сделать `contextManager` `var` и присваивать, либо делегировать в
`ContextManager.switchStrategy` (который уже умеет менять `strategy`).
См. [`critical-issues.md`](critical-issues.md) §1.

---

## 🟠 Средние

### 4. ✅ Утечка ресурсов: `HttpClient` и `JsonChatStore` не закрываются
**Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:80-204`
`client = OpenAiCompatibleClient(...)` (держит `HttpClient(CIO)`) и `memoryStore = JsonChatStore()`
(`AutoCloseable`, writer-актор + `SupervisorJob`) создаются в `run()`, но REPL выходит через
`break`/`return@runBlocking` без `finally`/`.close()`. Каждый выход оставляет висеть пулы
соединений (треды/сокеты) и writer-корутину. Pending-записи в `Channel.UNLIMITED` могут не
дренироваться → потеря данных при выходе сразу после сообщения.
**Фикс:** обернуть REPL-цикл в `try { ... } finally { memoryStore.close(); client.close() }`
(`OpenAiCompatibleClient` нужно сделать `AutoCloseable`, проксирующим `httpClient.close()`).

### 5. ✅ `choices.first()` бросает `NoSuchElementException` на пустом ответе
**Файлы:** `agent/ContextAwareAgent.kt:120`, `context/HistoryCompressor.kt:87,117`,
`agent/ProfileExtractor.kt:56`, `agent/SimpleAgent.kt:35`, `llm/BenchmarkRunner.kt:37`
`ChatResponse.choices` — `List<Choice>` без дефолта (`llm/model/ChatResponse.kt:9`). При пустом
`choices` (content-filter, тело-ошибка) `first()` бросает `NoSuchElementException`. В
`ContextAwareAgent.chat` (`:120`) это **не ловится** (ловят только `LlmCallException`) → роняет
весь чат. В классификаторах stages используется безопасный `firstOrNull()` — асимметрия.
**Фикс:** `result.data.choices.firstOrNull()?.message?.content` с обработкой null.

### 6. ✅ DEBUG-вывод сырого API-ответа в stderr (утечка данных + garbled TUI)
**Файл:** `src/main/kotlin/com/cliagent/llm/OpenAiCompatibleClient.kt:53`
`System.err.println("[DEBUG] Raw API response:\n$bodyText")` при любой ошибке парсинга. Сырой
body (может содержать контент диалога, иногда echoed sensitive-данные) льётся в stderr; в non-TTY
попадает в логи; в TTY гарблит терминал. Debug-вывод оставлен в продакшене.
**Фикс:** вынести за флаг `--debug`/env, либо писать только длину + первые N символов.

---

## 🟡 Низкие

### 7. ✅ `String.format` без явной локали — запятая вместо точки в `/cost`
**Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:461-463`
`String.format("%.2f", price.input)` без `Locale` использует `Locale.getDefault()`. На
`ru_RU`/`de_DE` → `$0,045000` вместо `$0.045000`.
**Фикс:** `String.format(java.util.Locale.US, "%.6f", ...)`.

### 8. ✅ `ConfigRepository.loadLocalProperties()` — относительный путь, зависит от cwd
**Файл:** `src/main/kotlin/com/cliagent/config/ConfigRepository.kt:31`
`java.io.File("local.properties")` разрешается от текущей директории процесса. Запуск не из корня
проекта (cron, другой cwd, IDE) → `local.properties` не найден → «API key not found» без
объяснения. Должно искаться детерминированно (от location jar / env / `AppPaths`).

### 9. ✅ `truncateToTokens` — некорректная edge-case логика при `maxTokens <= 0`
**Файл:** `src/main/kotlin/com/cliagent/llm/token/TokenCounter.kt:59-63`
`if (text.isEmpty() || maxTokens <= 0) return if (maxTokens <= 0) marker else text` — для пустой
строки при `maxTokens <= 0` возвращает голый `marker` (`"\n…[усечено]…"`); для непустого текста
при `maxTokens <= 0` тоже только marker, теряя весь контекст. Сейчас не триггерится
(`ArtifactLimits` константы положительны), но логика некорректна — стреляет при динамическом
бюджете=0. ⚠️ потенциальный.

### 10. ✅ `InvariantGuard` retry-loop копит мусор в history
**Файл:** `src/main/kotlin/com/cliagent/agent/InvariantGuard.kt:46-56`
Каждая retry-итерация вызывает `delegated.chat(feedbackMessage)`, а `ContextAwareAgent.chat`
сохраняет каждое сообщение в history. За 3 retry в истории оседает 8 сообщений (включая
нарушения). `turnCount++` срабатывает на каждый retry → лишние LLM-вызовы авто-профиля.
Деградация качества/производительности при упорных нарушениях инварианта.

### 11. ✅ `BranchingStrategy.currentBranchId` не восстанавливается после рестарта
**Файл:** `src/main/kotlin/com/cliagent/context/strategy/BranchingStrategy.kt:13-20`
`currentBranchId = "main"` всегда; `loadBranches()` грузит ветки, но не восстанавливает текущую.
После `cli-agent chat -c <id>` пользователь снова на `main` без предупреждения, хотя работал в
ветке. Данные не теряются (ветки сохранены), но состояние не восстанавливается. UX-баг.

### 12. ✅ Опечатка в `--strategy` молчаливо игнорируется
**Файл:** `src/main/kotlin/com/cliagent/cli/ChatCommand.kt:109`
`ReasoningStrategy.entries.find { it.label == strategy }` — unknown label → `null` → дефолтный
промпт без предупреждения. В отличие от `--context` (`:296` печатает «Unknown strategy»), для
`--strategy` нет валидации. Пользователь думает, что включил `step_by_step`, а работает дефолт.

### 13. ✅ `SummaryStrategy.clearSummaryIfRequested()` — мёртвый метод (latent-баг)
**Файл:** `src/main/kotlin/com/cliagent/context/strategy/SummaryStrategy.kt:35-40`
Метод декларирован, `reset()` выставляет флаг `summaryCleared`, но 0 callers — очистка summary
при переключении на summary-стратегию никогда не сработает. Мёртвый код + latent-баг.

### 14. ✅ `ProfileExtractor.parseProfile` теряет многострочные значения
**Файл:** `src/main/kotlin/com/cliagent/agent/ProfileExtractor.kt:83-107`
Парсер читает построчно: `about:`/`style:`/`format:` берут только `substringAfter(":").trim()`
одной строкой. Многострочное значение (частое для `about`) — последующие строки без маркера
теряются. Деградация качества авто-извлечения профиля, не runtime-ошибка.

### 15. ⚠️ `LlmInvariantChecker.parseVerdict` — `invariants.first()` без guard
**Файл:** `src/main/kotlin/com/cliagent/state/invariant/LlmInvariantChecker.kt:103`
`val rule = invariants.firstOrNull { it.id == ruleId } ?: invariants.first()`. Сейчас безопасно
благодаря fast-path `if (invariants.isEmpty()) return Valid` в `judge` (`:50`), но `parseVerdict`
не defensive — падает на пустом списке при изменении fast-path. Хрупкая связка.

### 16. ⚠️ `JsonChatStore.chatFile(chatId)` — нет санитизации chatId
**Файл:** `src/main/kotlin/com/cliagent/memory/JsonChatStore.kt:257`
`chatsDir.resolve("$chatId.json")` — `chatId` конкатенируется без проверки. `../` в chatId даёт
path traversal. В текущем использовании chatId — UUID или `-c` flag (добавляется `.json`, так что
`-c ../../../etc/passwd` → попытка удалить `/etc/passwd.json` — безвредно), реальной угрозы нет.
Defensive coding отсутствует. ⚠️ не эксплойтуема в текущем использовании.
