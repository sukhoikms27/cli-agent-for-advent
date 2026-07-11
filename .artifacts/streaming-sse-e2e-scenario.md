# E2E Scenario: Streaming SSE — Progressive токен-вывод

> Платформа: CLI (Kotlin/JVM). Требует: Ollama запущена локально (qwen3:14b).
> Источник правды для валидации.

## Чек-лист проблемы (что решаем)
- [x] токены идут progressively (пользователь видит инкремент, не «пустоту» 40с) ✅
- [x] socketTimeout 120с НЕ срабатывает на долгом запросе qwen3 thinking ✅ (52с запрос completed)

## Автоматические проверки (unit, MockEngine — без сети)
- [x] 1. `./gradlew clean build` — BUILD SUCCESSFUL, 0 failures ✅
- [x] 2. SSE-парсинг: deltas accumulate → Done с usage (MockEngine) ✅
- [x] 3. `[DONE]` без usage → Done(usage=null) ✅
- [x] 4. keep-alive comments skip ✅
- [x] 5. HTTP 500 → StreamChunk.Error (не падает) ✅
- [x] 6. content=null в delta → нет Delta-эмиссии ✅
- [x] 7. ConfigRepository: `CLI_AGENT_STREAM` env override + default "auto" ✅
- [x] 8. backward-compat: ChatRequest `stream=null` → поле НЕ пишется в JSON ✅

## E2E проверки (требует Ollama + qwen3:14b)
- [x] 9. `curl /v1/chat/completions` с `stream:true` — SSE-фреймы приходят ✅
   - data-фреймы с delta.content + delta.reasoning (qwen3 thinking), data: [DONE]
- [x] 10. REPL запрос на локальной модели → progressive вывод токенов ✅
   - raw progressive (streamPrint) + финальный markdown (streamFinalize)
- [x] 11. Финальный markdown-рендер после стрима ✅
   - переформатированный блок с переносами
- [x] 12. socketTimeout НЕ срабатывает (запрос 52с completes успешно) ✅
- [x] 13. `CLI_AGENT_STREAM=false` → non-streaming путь ✅
   - один блок ответа (без дублирования raw+markdown)
- [x] 14. `CLI_AGENT_STREAM=true` → стриминг (env override) ✅
- [x] 15. При активной задаче (stage-flow) → fallback на non-streaming ✅
   - `/task start` → блоки стадий через onEmit (не progressive), ноль регрессий

## Дополнительная находка (не в плане)
- [x] qwen3 thinking `delta.reasoning` — Ollama отдаёт reasoning отдельно от content.
  Добавлен StreamChunk.Reasoning + onReasoning колбэк + streamPrintReasoning (серый цвет).
  Пользователь видит progressive «💭 thinking…» ДО финального ответа.

## Архитектурные инварианты (проверены)
- `CancellationException` не глотается (re-throw в Flow collect) ✅
- backward-compat: non-streaming `chat()` идентичен дням 1-30 ✅
- MVP: стриминг только прямого чата (tools/stage-flow → fallback) ✅
