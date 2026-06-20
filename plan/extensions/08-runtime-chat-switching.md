# Дизайн: Переключение/создание чата в runtime (доп. п.8)

**Дата:** 2026-06-20
**Статус:** Draft (ожидает review)
**Тип:** Самостоятельное расширение (UX-polish; не входит в курс дня 15, отложено из декомпозиции)

## Контекст и мотивация

Сейчас нельзя переключиться на другой чат или создать новый, не перезапуская программу. Команда
`/chats` только выводит список (read-only). `chatId` фиксируется в `ContextAwareAgent` (val) и в
`ChatCommand.run()` (val). `agent.reset()` НЕ заменяет смену чата — он уничтожает текущий чат на диске
и оставляет в нём.

Цель: команды `/chat list`/`/chat new`/`/chat switch <id>` для управления чатами в runtime, не выходя
из REPL.

## Текущее состояние (из исследования) — три блокирующих фактора

1. **`chatId` — `private val` в `ContextAwareAgent`** (строка 29). Нет сеттера, нет метода `switchChat`.
2. **Нет slash-команды** — только read-only `/chats` (строки 293–307). Команд `/chat switch`/`new` нет
   ни в `when`, ни в completer.
3. **История в RAM + флаг `loaded`** — `ensureLoaded()` грузит один раз; без сброса `loaded=false` и
   очистки `history` агент продолжит со старым снимком.

Дополнительно найден **баг `switchStrategy`** (строки 218–228): метод принимает `newManager`,
загружает в него факты/ветки, но **не переприсваивает поле `contextManager`** (оно `val`). Команда
`/strategy` фактически не меняет стратегию — тот же баг помешает runtime-смене чата (стратегии тоже
привязаны к `chatId`).

Все CRUD-операции в `MemoryStore` уже есть (`listChats`/`createChat`/`loadChat`/`loadHistory`/
`loadSummary`/`loadFacts`/`loadWorkingMemory`/`listBranches`) — хранилище трогать не нужно.

## Решения

1. **`chatId: var` + метод `switchChat(newId)` в `ContextAwareAgent`.** Меняет id, сбрасывает `loaded`,
   очищает in-memory историю, перезагружает `workingMemory`/факты/ветки для нового id.
2. **Пересоздание `contextManager`** при смене чата — стратегии (`SummaryStrategy`/`BranchingStrategy`)
   захардкожены на стартовый `chatId`. Сделать `contextManager: var` (заодно фиксит баг `switchStrategy`)
   и пересоздавать через `createStrategy(...)`.
3. **Команды `/chat list`/`new`/`switch <id>`** в `ChatCommand` + completer.
4. **Сохранение текущего чата перед переключением** — `agent` уже персистит каждое сообщение
   (`saveMessage`); дополнительно `flushWorkingMemory` перед switch (на всякий случай).
5. **Long-term memory не меняется** — она global (корректно; профиль/инварианты общие для всех чатов).

## Архитектура

### `ContextAwareAgent.switchChat` (правка)

```kotlin
class ContextAwareAgent(
    ...
    private var chatId: String,          // было val → var
    private var contextManager: ContextManager? = null   // было val → var (фиксит баг switchStrategy)
) : Agent {

    /**
     * Смена активного чата в runtime (extension п.8).
     *
     * @param newId  id нового чата (должен существовать в MemoryStore)
     * @param newManager  пересозданный ContextManager с новым chatId (стратегии привязаны к chatId)
     */
    suspend fun switchChat(newId: String, newManager: ContextManager? = null) {
        chatId = newId
        history.clear()
        loaded = false                    // следующий ensureLoaded перечитает loadHistory(newId)
        workingMemory = null
        contextManager = newManager ?: contextManager   // пересоздание если передано
        ensureLoaded()                    // грузит history/workingMemory/фacts/branches для newId
        tokenCounter.reset(newId)         // обнулить статистику для нового чата
    }
}
```

### Фикс `switchStrategy` (баг)

```kotlin
suspend fun switchStrategy(newManager: ContextManager) {
    // было: не переприсваивалось contextManager (val) → стратегия не менялась
    (newManager.getStrategy() as? StickyFactsStrategy)?.let {
        it.setFacts(memoryStore.loadFacts(chatId))
    }
    (newManager.getStrategy() as? BranchingStrategy)?.let { it.loadBranches() }
    contextManager = newManager           // ДОБАВИТЬ: теперь var, переприсваивается
}
```

### Команды `/chat` в `ChatCommand`

```kotlin
input.startsWith("/chat") -> handleChat(input, agent, memoryStore)

private fun handleChat(input: String, agent: StatefulAgent, memoryStore: MemoryStore) {
    val parts = input.split(Regex("\\s+"))
    when {
        parts.size == 1 || parts[1] == "list" -> printChats(memoryStore)
        parts[1] == "new" -> {
            val newChat = memoryStore.createChat()
            val newManager = createStrategy(contextStrategy, client, model, memoryStore, newChat.id, keepRecent)
            agent.contextAware.switchChat(newChat.id, newManager)
            currentChatId = newChat.id        // var в ChatCommand.run()
            AppTerminal.ok("Switched to new chat: ${newChat.id}")
        }
        parts[1] == "switch" && parts.size >= 3 -> {
            val id = parts[2]
            if (memoryStore.loadChat(id) == null) {
                AppTerminal.warn("Chat not found: $id. Use /chat list.")
                return
            }
            val newManager = createStrategy(contextStrategy, client, model, memoryStore, id, keepRecent)
            agent.contextAware.switchChat(id, newManager)
            currentChatId = id
            AppTerminal.ok("Switched to chat: $id")
        }
        else -> AppTerminal.println("Usage: /chat [list|new|switch <id>]")
    }
}
```

`currentChatId` в `ChatCommand.run()` — `var` (был `val`); обновляется при switch; используется в
заголовке (`Chat: $currentChatId`).

### Completer

```kotlin
val chat = ArgumentCompleter(
    StringsCompleter("/chat"),
    StringsCompleter("list", "new", "switch")
)
```
`/chat` в `top` StringsCompleter; `chat` в `AggregateCompleter`.

## Компоненты

| Компонент | Ответственность |
|---|---|
| `ContextAwareAgent.switchChat(newId, newManager)` (новый) | Смена чата: chatId, сброс loaded/history, перезагрузка. |
| `ContextAwareAgent.chatId`/`contextManager` (правка val→var) | Мутабельность для switch. |
| `ContextAwareAgent.switchStrategy` (правка, баг-фикс) | Переприсваивание `contextManager`. |
| `ChatCommand.handleChat` (новый) | `/chat list`/`new`/`switch`. |
| `ChatCommand.currentChatId` (правка val→var) | Обновление заголовка при switch. |
| `ReplEngine` completer (правка) | `/chat` + подкоманды. |

## Риски

- **Баг `switchStrategy` вскрывается** — val→var для `contextManager` меняет поведение `/strategy`
  (теперь реально работает). Проверить существующие тесты `switchStrategy` (если есть) — возможно,
  они тестировали «не меняется» (баг как фича). Обновить.
- **`tokenCounter.reset(newId)`** — статистика токенов per-chat в `TokenCounter` (in-memory map по
  sessionId=chatId). При switch сбрасываем для нового; старый сохраняется в map (можно вернуться).
- **Branching strategy** — ветки per-chat; `loadBranches()` в `switchChat` для нового chatId. Проверить,
  что `BranchingStrategy.loadBranches()` читает по `chatId` (а не захардкожен старый).
- **Гонки при switch** — если switch во время LLM-вызова (фоновой корутины), `chatId` меняется
  mid-flight. Митигация: switch только в главном потоке REPL (между ходами), не во время `chat()`.
- **`currentChatId` vs `chatId` в `createStrategy`** — при switch пересоздаём manager с новым id;
  убедиться, что все ссылки используют обновлённое значение.

## Тестирование

- `ContextAwareAgent.switchChat` тест: chatId меняется, history перезагружается из нового чата,
  workingMemory из нового, facts/branches из нового, loaded=true после.
- `switchStrategy` фикс-тест: после switch `contextManager` реально новый (не старый).
- `handleChat` (если тестируется): `/chat new` создаёт + switch; `/chat switch <bad>` → warn;
  `/chat list` → список.
- Интеграция: chat A (сообщения) → `/chat new` → chat B пустой → `/chat switch A` → chat A сообщения
  восстановлены.

## Вне скоупа

- Переименование/удаление чатов (`/chat rename`/`delete`) — `MemoryStore.deleteChat` есть, можно
  добавить позже.
- Поиск по чатам (`/chat search <query>`).
- Tab-completion id чатов в `/chat switch` (динамический completer из `listChats()`).

## Критерии приёмки

- [ ] `/chat new` создаёт чат и переключается (заголовок `Chat: <newid>`).
- [ ] `/chat switch <id>` переключается на существующий; история восстановлена.
- [ ] `/chat switch <bad>` → warn, текущий чат не меняется.
- [ ] `/chat list` (или `/chat`) → список чатов.
- [ ] Long-term memory (профиль/инварианты) НЕ меняется при switch (global).
- [ ] Баг `switchStrategy` фиксирован — `/strategy` реально меняет стратегию.
- [ ] Completer `/chat` + `list`/`new`/`switch`.
- [ ] `./gradlew test build` зелёный.

## Зависимости

- `ContextAwareAgent` (val→var, новый метод).
- `ChatCommand` (новая команда, var currentChatId).
- `ReplEngine` (completer).
- `MemoryStore` (CRUD уже есть — не трогать).
- Багфикс `switchStrategy` — побочный, но необходимый (тот же val-барьер).
- `createStrategy` (в `ChatCommand`, строки 190–192) — переиспользуется для пересоздания manager.
