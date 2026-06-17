# Задача 02. Хранилище working + long-term памяти

## Цель
Разделить хранение слоёв: working — per-chat в `ChatData`, long-term — global отдельный файл. Расширить `MemoryStore` и `JsonChatStore`.

## Файлы

### `config/AppPaths.kt` (правка)
Добавить пути global long-term (XDG-compliant):
```kotlin
val longTermDir: Path get() = dataDir.resolve("longterm")
val longTermFile: Path get() = longTermDir.resolve("memory.json")
```

### `memory/ChatData.kt` (правка)
Добавить defaulted-поле (эволюция схемы — старые чаты грузятся):
```kotlin
val workingMemory: WorkingMemory? = null
```

### `memory/MemoryStore.kt` (правка)
Добавить методы (сгруппировать комментариями по слоям):
```kotlin
// Working memory — per-chat (данные текущей задачи)
suspend fun saveWorkingMemory(chatId: String, memory: WorkingMemory)
suspend fun loadWorkingMemory(chatId: String): WorkingMemory?
suspend fun clearWorkingMemory(chatId: String)

// Long-term memory — global (profile, decisions, knowledge)
suspend fun loadLongTermMemory(): LongTermMemory          // non-null: пустой если файла нет
suspend fun saveLongTermMemory(memory: LongTermMemory)
suspend fun clearLongTermMemory()
```

### `memory/JsonChatStore.kt` (правка)
- Конструктор принимает инжектируемый long-term стор (для тестируемости с temp-директориями; `ChatCommand` использует дефолт):
  ```kotlin
  class JsonChatStore(
      private val chatsDir: Path = AppPaths.chatsDir,
      private val longTermStore: JsonLongTermStore = JsonLongTermStore()
  ) : MemoryStore
  ```
- `saveWorkingMemory/loadWorkingMemory/clearWorkingMemory` — по существующему read-modify-write паттерну `copy(workingMemory=..., updatedAt=Instant.now().toString())` + `atomicWrite` (зеркало `saveSummary`/`clearSummary`).
- `loadWorkingMemory` возвращает `loadChat(chatId)?.workingMemory`.
- Long-term: `load/save/clearLongTermMemory` форвардятся в `longTermStore` (параметр конструктора, не внутренний объект — иначе `JsonChatStore` хардкодил бы реальный `AppPaths.longTermFile` и был нетестируем с temp-директориями).

### `memory/JsonLongTermStore.kt` (новый)
Global long-term стор, отдельный класс (не смешивать chat-scoped и global):
- Тот же `Json`-конфиг (`ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls=false`, `prettyPrint=true`).
- `ensureDir()` для `AppPaths.longTermDir`; atomic write (temp+move) в `AppPaths.longTermFile`.
- `load(): LongTermMemory` → `LongTermMemory()` при отсутствии файла; `runCatching` на повреждённый файл (как `loadChatInternal`).
- `save(memory)` — whole-aggregate atomic write.
- `clear()` — `LongTermMemory()` (перезаписать пустым) или удалить файл.
- Всё в `withContext(Dispatchers.IO)`.

## Конвенции
- Атомарная запись (temp + `ATOMIC_MOVE` + `REPLACE_EXISTING`) — обязательно.
- `suspend` + `Dispatchers.IO` для IO.
- Частичные апдейты long-term (добавить ключ) делает слой команд (задача 05), стор хранит весь агрегат — как сейчас с `facts`.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `JsonChatStore` реализует все 6 новых методов интерфейса (иначе abstract method error).
- Старый чат без `workingMemory` грузится (`workingMemory == null`).
- Long-term файл создаётся в `$dataDir/longterm/memory.json`, не попадает в `listChats()` (он в другом каталоге).

## Зависимости
Задача 01 (модели слоёв).
