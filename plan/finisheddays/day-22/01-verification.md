# День 22 — Верификация

## Сборка и тесты

```
./gradlew build
```

Результат: **BUILD SUCCESSFUL**, 0 регрессий.

### Новые тесты (13)

**`RagRetrieverTest`** (4 теста, `src/test/kotlin/com/cliagent/rag/RagRetrieverTest.kt`):
- `retrieve returns null when index has no embedded chunks` — пустой индекс → null.
- `retrieve returns null on embedding error — graceful degradation` — FailingEmbedder → null.
- `retrieve returns top-K chunks sorted by descending score` — нормальный путь, сортировка.
- `retrieve respects topK limit` — лимит K.

**`PromptBuilderTest`** (+4 теста, `src/test/kotlin/com/cliagent/agent/PromptBuilderTest.kt`):
- `null retrieved context produces base content — day 22 zero regression` — байт-идентичность.
- `empty retrieved context list does not render the block` — пустой список → нет блока.
- `retrieved context renders block with source and section` — рендер source › section.
- `retrieved context block is between working and invariants — day 22 ordering` — порядок блоков.

**`ContextAwareAgentRagTest`** (5 тестов, `src/test/kotlin/com/cliagent/agent/ContextAwareAgentRagTest.kt`):
- `rag disabled does not call retriever` — off → retriever не вызывается, блока нет.
- `rag enabled injects retrieved context into system prompt` — on → блок в system-prompt.
- `rag enabled but retriever returns null degrades gracefully without block` — мягкая деградация.
- `rag enabled but null retriever reports disabled and does not inject` — isRagEnabled учитывает retriever.
- `setRagEnabled toggles runtime state` — toggle on/off.

Проверка запуска:
```
./gradlew :test --tests "com.cliagent.rag.RagRetrieverTest" \
               --tests "com.cliagent.agent.PromptBuilderTest" \
               --tests "com.cliagent.agent.ContextAwareAgentRagTest"
→ BUILD SUCCESSFUL
```

## Acceptance checklist (из плана)

- [x] `./gradlew build` зелёный, 0 регрессий.
- [x] `RagRetriever` инкапсулирует retrieval (DRY, переиспользуется агентом и `/rag eval`).
- [x] `PromptBuilder` рендерит `[Retrieved context]` (null → zero-regression).
- [x] `ContextAwareAgent` retrieve'ит каждый ход при `ragEnabled`, мягко деградирует при ошибке.
- [x] `/rag on|off` — runtime toggle (дефолт из `RagConfig.enabled`).
- [x] `/rag eval` — прогон 10 вопросов, сравнительный отчёт.
- [x] `resources/rag/eval-questions.json` — 10 контрольных вопросов (keywords + sources).
- [x] `/rag` completer добавлен в `ReplEngine` (упущение дня 21 закрыто).
- [x] 13 новых тестов зелёные.
- [x] Комментарии-заглушки «день 22» обновлены (`AppConfig.kt`, `RagCommands.kt`, `ChatCommand.kt`).

## Ручная проверка (требует Ollama + собранный индекс)

> Требуется: Ollama запущена (`ollama serve`), `nomic-embed-text` спулен, индекс собран `/rag index`.

1. `/rag index` → индекс корпуса проекта.
2. `/rag on` → «RAG injection: ON».
3. Вопрос по кодовой базе, напр. «Какие 4 стратегии управления контекстом есть в проекте?».
4. Ожидание: в system-prompt (видно в debug-дампе) — блок `[Retrieved context]` со ссылками на
   `global-plan.md` / `AGENTS.md`; ответ опирается на чанки (anti-hallucination).
5. `/rag off` → тот же вопрос → ответ без RAG (может галлюцинировать / быть менее точным).
6. `/rag eval` → таблица: для каждого из 10 вопросов — no-RAG keywords vs RAG keywords (Δ).

## Мягкая деградация (без Ollama)

- `/rag on` + вопрос → `RagRetriever.retrieve()` = null (Ollama недоступна) → агент отвечает без
  RAG-блока, без краша (поведение дней 1–21).
- `/rag eval` без индекса → «No embedded index. Use: /rag index first.»

## Границы подтверждены

- Реранкинг / порог similarity / query rewrite — день 23 (не реализован).
- Обязательные цитаты / режим «не знаю» — день 24 (не реализован).
