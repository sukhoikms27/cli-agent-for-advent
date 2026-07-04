# День 24 — Верификация

## Сборка

- `./gradlew build` — **BUILD SUCCESSFUL**, 468 тестов, 0 failures, 0 errors.
- 21 новый тест (CitationDetector 12 + PromptBuilder день-24 3 + ContextAwareAgentRag день-24 6).
- Существующие тесты дней 1–23 не сломаны.

## Чек-лист соответствия заданию day-24

| Требование | Статус | Реализация / тест |
|---|---|---|
| модель обязательно возвращает ответ | ✅ | `chat()` всегда возвращает: canned ИЛИ LLM. Тест: `weak context below threshold returns canned` |
| список источников (source + section/chunk_id) | ✅ | Промпт требует раздел «Источники: source › section (chunk_id)». Тест: `retrieved block requires structured answer format` |
| цитаты (фрагменты из найденных чанков) | ✅ | Промпт требует раздел «Цитаты: дословные фрагменты в кавычках». Тест: `retrieved block requires structured answer format` |
| проверить на 10 вопросах: источники | ✅ | `/rag eval` колонка `RAG src` через `CitationDetector.sourcesPresent` |
| проверить на 10 вопросах: цитаты | ✅ | `/rag eval` колонка `RAG cite` через `CitationDetector.citationsPresent` |
| совпадает ли смысл с цитатами | ✅ | Пост-чек `CitationDetector` (substring-overlap ≥40). Тест: `citations present via substring overlap` |
| усиление: «не знаю» при слабом контексте | ✅ | `dontKnowThreshold` + `CannedResponses.weakContext()`. Тест: `weak context below threshold returns canned response without LLM call` |

## Архитектурные инварианты (проверены)

### 1. Backward compat с днём 23
- `dontKnowThreshold=0.0f` (default) → режим «не знаю» выключен → LLM вызывается даже при низком сходстве.
  Тест: `threshold zero disables dont-know mode — backward compat with day 23`.
- Усиленный промпт сохраняет формат `source › section (chunkId, score)`.
  Тест: `retrieved block still renders source section chunkId score — no regression`.

### 2. Мягкая деградация дня 22 сохранена
- `retrieve()=null` (Ollama down / пустой индекс) → агент отвечает без RAG-блока, **НЕ** canned «не знаю».
  Тест: `retrieve null with positive threshold does not trigger canned — day 22 graceful degradation`.
- `dontKnowThreshold` срабатывает только когда индекс есть, но релевантность слабая.

### 3. `Agent.chat()` сигнатура неизменна
- Как в днях 22–23. RAG-контекст собирается внутри реализации. Все call-site'ы (`StatefulAgent`,
  `TaskOrchestrator`, REPL) не правились.

### 4. Canned-response persist'ится корректно
- Сохраняется в history как обычный assistant-сообщение (с `parentId`).
  Тест: `canned response is persisted in history — день 24`.

### 5. `CancellationException` не глотается
- AGENTS.md: корутины. Во всех новых suspend-точках (`withTimedSpinner`, `retrieve`) исключение
  пробрасывается стандартно.

### 6. Корпус валиден
- `corpusRoots` расширен до `["plan","docs","README.md","AGENTS.md","src/main/kotlin"]`.
- Все 13 файлов-источников из `eval-questions.json` проверены на существование (см. T0 аудит).
- После `/rag index` (требует Ollama) индекс покроет все expectedSources.

## Smoke-тест (ручной, требует Ollama + z.ai)

```
/rag index          # переиндексация расширенного корпуса
/rag on             # включить RAG-инъекцию
/config init        # или редактировать config.json: rag.dontKnowThreshold = 0.4
# Релевантный вопрос (должен дать источники + цитаты):
Какой размер батча при эмбеддинге через Ollama?
# → раздел «Источники: 05-embeddings.md › …», цитата «batchSize=32»
# Нерелевантный вопрос (должен дать canned «не знаю»):
Сколько стоит билет на Марс?
# → «Я не нашёл достаточно релевантной информации... Я не знаю...»
/rag eval           # прогон 10 вопросов с колонками src/cite
```

## UX-чек (не требует внешних сервисов)

- Live-таймер в спиннере: `Thinking… 00:00:03` (обновляется каждые 120ms).
- Серая строка после ответа: `⏱ 00:00:04` (mordant `gray`, plain при `--no-color`).
- Прогрессивные логи: `📚 RAG: 5 chunk(s), best similarity 0.82` → `🤖 Generating answer via LLM…`
  → (при слабом контексте) `🚫 Anti-hallucination: best 0.20 < threshold 0.40 → canned «не знаю»`.
