# Streaming SSE — Progressive токен-вывод для локальной LLM

> **Дата:** 2026-07-11
> **Задача:** Решить проблему socketTimeout + «пустоты» при долгих ответах локальной модели (qwen3:14b 40-90с)
> **Ветка:** `task/streaming-sse` (от `swarm-dev`) → merge в `swarm-dev`
> **Статус:** Done

## Проблема

qwen3:14b thinking-модель отвечает 40-90с на сложные промпты. Два следствия:
1. `socketTimeoutMillis = 120_000` в `OpenAiCompatibleClient` срабатывал → запрос падал.
2. Пользователь видел «пустоту» (спиннер) до 90с, не понимая работает ли модель.

При стриминге (`stream: true`) токены идут по мере генерации → `socketTimeout` измеряет время
**между байтами** (сбрасывается при каждом токене) → не срабатывает; пользователь видит progressive ответ.

## План (одобрен с верификацией)

Вариант (A): новый метод `chatStream(): Flow<StreamChunk>` рядом с `chat()` — не ломает существующий
контракт. MVP scope: стриминг только прямого чата без tools/stage-flow; fallback — ноль регрессий.

## Что реализовано

### Созданные файлы (2)
- `src/main/kotlin/com/cliagent/llm/model/StreamChunk.kt` — sealed `StreamChunk` (Delta/Reasoning/Done/Error),
  `StreamOptions`, SSE-модели парсинга (`StreamChatChunk`/`StreamChoice`/`StreamDelta` с `reasoning` полем).
  Переиспользует существующий `Usage`.
- `src/test/kotlin/com/cliagent/llm/OpenAiCompatibleClientStreamTest.kt` — 9 SSE-тестов (MockEngine DI).

### Изменённые файлы (8)
- `llm/LlmClient.kt` — `fun chatStream(request): Flow<StreamChunk>` (placeholder раскомментирован).
- `llm/model/ChatRequest.kt` — `stream: Boolean? = null` + `streamOptions: StreamOptions? = null` (null → не пишется → backward-compat).
- `llm/OpenAiCompatibleClient.kt` — DI-конструктор HttpClient; `chatStream` (без retry); `executeStream`
  через `HttpStatement.execute { bodyAsChannel().readLine() }`; per-request `INFINITE_TIMEOUT_MS`;
  `flowOn(Dispatchers.IO)`.
- `config/AppConfig.kt` — `stream: String = "auto"` (schema evolution).
- `config/ConfigRepository.kt` — env `CLI_AGENT_STREAM` override.
- `cli/AppTerminal.kt` — `streamPrint` (raw progressive) + `streamPrintReasoning` (серый) + `streamFinalize` (markdown).
- `agent/ContextAwareAgent.kt` — `chatStreamed(userMessage, onToken, onReasoning?)`: повторяет логику
  `chat()`, финальный LLM-вызов через `chatStream` + collect. tools → fallback на `runToolLoop`.
- `cli/ChatCommand.kt` — `streamEnabled` (auto → OLLAMA); streaming-путь в `dispatchFreeText` с
  `onReasoning` (💭 thinking… приглушённым); fallback при активной задаче.

## Ключевые находки и решения

1. **`delta.reasoning` — qwen3 thinking** (не в плане, найдено в E2E): Ollama отдаёт thinking-контент в
   `delta.reasoning` отдельно от `delta.content`. Без поддержки reasoning стриминг thinking-модели = пустота
   (весь смысл стриминга теряется). Добавлен `StreamChunk.Reasoning` + `onReasoning` колбэк + серый progressive вывод.
2. **Ktor 3.4.3 `requestTimeoutMillis = 0` бросает `IllegalArgumentException`** (`require(value > 0)`).
   Использован `HttpTimeoutConfig.INFINITE_TIMEOUT_MS` (Long.MAX_VALUE) → Ktor внутренне конвертирует в 0 для engine.
3. **`readUTF8Line` deprecated** в Ktor 3.4.3 → `readLine()` (тот же пакет `io.ktor.utils.io`).
4. **`HttpStatement.execute { }` lambda suspend** — `emit()` работает внутри (подтверждено из Ktor 3.4.3).
5. **Ollama НЕ шлёт usage** в финальном SSE-фрейме (несмотря на `stream_options`) → `Done(usage=null)`,
   `recordUsage(null)` — graceful, метрики неточны но функциональность сохранена.

## Результаты Validation

- **`./gradlew clean build` — BUILD SUCCESSFUL**, 578 тестов, 0 failures (566 baseline + 12 новых).
- **E2E-сценарий** (15/15 шагов, `.artifacts/streaming-sse-e2e-scenario.md`):
  - Шаг 9: `curl stream:true` — SSE-фреймы приходят (data + delta.content + delta.reasoning + [DONE]).
  - Шаг 10-11: REPL qwen3:14b → progressive raw вывод + финальный markdown-рендер.
  - Шаг 12: socketTimeout НЕ срабатывает (запрос 52с completed успешно — раньше падал бы).
  - Шаг 13: `CLI_AGENT_STREAM=false` → non-streaming (один блок, backward-compat).
  - Шаг 14: `CLI_AGENT_STREAM=true` → стриминг (env override).
  - Шаг 15: активная задача (`/task start`) → fallback на non-streaming stage-flow (ноль регрессий).

## Архитектурные решения

1. **Вариант (A) — `chatStream()` рядом с `chat()`**: не ломает существующий контракт. Agent-layer,
   stage-flow, tools — все остаются на `chat()`. Вызывающий код (REPL) выбирает стримить или нет.
2. **MVP scope — только прямой чат**: tools/stage-flow → fallback. Ноль регрессий для сложных путей.
3. **Auto-detect streaming**: `stream="auto"` → `provider==OLLAMA` (локальные модели медленные).
   Cloud (glm-5.1) → off (быстрый, SSE не нужен). Ручной override через env/config.
4. **Progressive render**: raw `streamPrint` (токены по мере поступления) + `streamFinalize` (markdown в конце).
   Дублирование (raw + markdown) — сознательный trade-off за live-feedback.
5. **Reasoning support**: `StreamChunk.Reasoning` + серый `streamPrintReasoning` + `💭 thinking…` разделитель.
   Qwen3 thinking виден progressively ДО финального ответа.
6. **Per-request infinite timeout** для стриминга (не глобальный) — non-streaming `chat()` сохраняет 120с защиту.

## Границы (Phase 2.0 — не входит)
- Стриминг в stage-flow/оркестраторе (Phase 2.1) — рой/AUTO-чейн garble.
- Стриминг tool_calls (Phase 2.2) — tool-итерации через обычный `chat()`.
- Ctrl+C отмена активного стрима (`currentJob?.cancel()`) — требует инфраструктуры REPL Job.
- Throttled markdown re-render (ANSI cursor) — plain progressive + final markdown достаточно.
