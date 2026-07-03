# День 22 — Первый RAG-запрос (README / общий план)

> Пайплайн RAG-инференса: **запрос → поиск релевантных чанков → объединение с вопросом → запрос к LLM**.
> Надстройка над днём 21 (индексация): retrieval теперь доходит до агента и инжектируется в промпт.
> Корневой контекст: [`00-task.md`](./00-task.md).

## Задание дня 22

- Реализовать: `вопрос → поиск чанков → объединение с вопросом → запрос к LLM`.
- Сравнить ответ модели **без RAG** vs **с RAG**.
- Усиление: 10 контрольных вопросов (ожидание + ожидаемые источники).
- Результат: **агент с двумя режимами + 10 контрольных вопросов + сравнение качества**.

## Что произошло с архитектурой

Day 22 — **второй из двух RAG-дней недели 5**. День 21 построил write-side (индексация), но
намеренно оставил зазор: retrieval существовал изолированно (`/rag search`), не доходя до агента.
Комментарии-заглушки «Инъекция в промпт — день 22» были в `RagCommands.kt`, `AppConfig.kt`,
`ChatCommand.kt`. Этот день закрывает зазор.

| Решение | Обоснование |
|---|---|
| **`RagRetriever` — отдельный класс** | До этого паттерн `load → embed(query) → topK` был размазан по `RagCommands.handleSearch` и `ChunkingComparison.probe`. Выделен в одно место (DRY), переиспользуется агентом и `/rag eval`. |
| **Инъекция через `PromptBuilder`** | Слоёный system-prompt уже имел seam: `base → long-term → working → invariants`. Добавлен блок `[Retrieved context]` между `working` и `invariants` (задача задаёт фрейм, чанки дают факты, инварианты — последними для recency). |
| **`Agent.chat()` сигнатура не меняется** | RAG-контекст собирается **внутри** реализации (агент сам retrieve'ит по `userMessage`), а не через параметры. Текущий ход → свежий retrieval (лекция недели 5: инференс-тайм подгрузка). |
| **Runtime toggle `/rag on|off`** | Дефолт из `RagConfig.enabled`, переопределяется в сессии. Быстрое A/B-сравнение в одном чате — точно под требование «два режима + сравнение». |
| **Мягкая деградация** | Ошибка эмбеддинга / пустой индекс → `retrieve() = null` → агент отвечает без RAG-блока (как `toolExecutor` в день 17). Ollama недоступна — агент не падает. |
| **`/rag eval` — авто-прогон 10 вопросов** | JSON-вопросы в classpath (`rag/eval-questions.json`): ожидаемые keywords + источники. Прогон в обоих режимах → сравнительный отчёт по покрытию keywords. Воспроизводимо, под регрессионное тестирование. |

## Поток данных (новый — день 22)

```
userMessage
   │
   ├─ if ragEnabled && ragRetriever != null:
   │     RagRetriever.retrieve(userMessage)
   │        ├─ JsonRagStore.load() → RagIndex
   │        ├─ embedder.embed([query]) → qVec  (Ollama /api/embed)
   │        └─ topK(qVec, index.chunks, k=topK) → List<ScoredChunk>
   │
   ├─ PromptBuilder(baseSystem, longTerm, working, ragContext).build()
   │     → base + [Long-term] + [Working] + [Retrieved context] + [Project invariants]
   │
   └─ runToolLoop(messagesToSend, ...) → LlmClient.chat(request) → ответ
```

## Карта изменений

| Файл | Тип | Изменение |
|---|---|---|
| `rag/RagModels.kt` | правка | `RagConfig += topK: Int = 5, injectIntoPrompt: Boolean = true` (schema-evolution safe) |
| `rag/RagRetriever.kt` | **новый** | `class RagRetriever(embedder, store, topK)` + `suspend fun retrieve(query): List<ScoredChunk>?` |
| `agent/PromptBuilder.kt` | правка | +параметр `retrievedContext`, блок `[Retrieved context]` + `renderRetrievedBlock()` |
| `agent/ContextAwareAgent.kt` | правка | +`ragRetriever`, `ragEnabled`; retrieval в `chat()`; `setRagEnabled/isRagEnabled`; проброс в `buildMessagesToSend` |
| `cli/ChatCommand.kt` | правка | сборка `ragEmbedder` + `RagRetriever`, wiring в агент, закрытие embedder в `finally` |
| `cli/RagCommands.kt` | правка | +`on|off|eval`, runtime-статус в summary, `topK`/`injectIntoPrompt` в config |
| `cli/ReplEngine.kt` | правка | +`/rag` completer (упущение дня 21) |
| `resources/rag/eval-questions.json` | **новый** | 10 контрольных вопросов по кодовой базе проекта |
| `test/.../rag/RagRetrieverTest.kt` | **новый** | 4 теста: пустой индекс, ошибка, сортировка, topK-лимит |
| `test/.../agent/PromptBuilderTest.kt` | правка | +4 теста RAG-блока (zero-regression, empty, рендер, ordering) |
| `test/.../agent/ContextAwareAgentRagTest.kt` | **новый** | 5 тестов: off, on+inject, graceful-null, null-retriever, toggle |

## Команды CLI (день 22)

- `/rag on` / `/rag off` — toggle RAG-инъекции в промпт (runtime, текущая сессия).
- `/rag eval` — прогон 10 контрольных вопросов с/без RAG + сравнительный отчёт.
- `/rag search <query>` — пробный retrieval top-K без агента (день 21, smoke-test).
- `/rag index|stats|compare|config` — индексация/статистика (день 21, без изменений).
- `/rag` (без аргументов) — сводка: runtime-режим + config + текущий индекс.

## Верификация

- `./gradlew build` — green, 0 регрессий (см. [`01-verification.md`](./01-verification.md)).
- 13 новых RAG-тестов (4 + 4 + 5) зелёные.
- `/rag index` → `/rag on` → вопрос по кодовой базе → блок `[Retrieved context]` в ответе.
- `/rag off` → тот же вопрос → ответ без RAG-блока.
- При отсутствии Ollama — `/rag on` + вопрос → мягкая деградация (ответ без блока, без краша).

## Границы (не в день 22)

- **Реранкинг / CrossEncoder / порог similarity / query rewrite** — день 23.
- **Обязательные цитаты + режим «не знаю» при слабом контексте** — день 24.
- Streaming, новый провайдер эмбеддингов — вне scope.
