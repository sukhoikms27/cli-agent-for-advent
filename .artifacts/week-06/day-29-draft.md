# День 29 — Оптимизация локальной LLM (ЧЕРНОВИК плана)

> Неделя 6, задание (оптимизация) из `plan/newdays/day29.md`

## Задание

🔥 **День 29. Оптимизация локальной модели**

Оптимизируйте локальную модель под свою задачу:
- 👉 настройте параметры (temperature, max tokens, context window)
- 👉 попробуйте квантование (если доступно)
- 👉 измените prompt-шаблон под конкретный кейс

Сравните:
- 👉 качество ответов до оптимизации
- 👉 качество после
- 👉 скорость и потребление ресурсов

**Результат:** Оптимизированная локальная LLM под конкретную задачу.

## Контекст кодовой базы (из Research)

Точки оптимизации уже есть в коде:
- **`OutputBudget.maxTokensFor(modelId, promptTokens)`** (`llm/token/OutputBudget.kt:23-56`) — per-model лимиты из `ModelLimitsRegistry`. qwen3 → 128K/8K.
- **`GenerationPresets`** (`llm/model/GenerationPresets.kt:8-36`) — STANDARD/CONCISE/PROMPTED/EXPERTS, разные temperature/maxTokens.
- **`ChatRequest`** — temperature, top_p, max_tokens (плоские поля, не GenerationConfig).
- **`PromptBuilder`** — инъекция system prompt, RAG-контекста, памяти.
- **ModelLimitsRegistry** — можно донастроить под qwen3:14b (context 40K по Ollama, а не 128K дефолт).

Квантование: модель `qwen3:14b` уже Q4_K_M (из Ollama). Сравнение Q4 vs Q8 возможно (`ollama pull qwen3:14b-q8_0` если есть), но это скорее задание по конфигурации Ollama, не коду.

## Что добавить в код

1. **Task-specific пресеты** — новые `GenerationPresets` под конкретные кейсы:
   - `RAG_LOCAL` — низкая temperature (0.3), строгий формат (источники), укороченный output (512) для скорости local.
   - `CLASSIFY` — temperature 0.1, maxTokens 50, для классификации.
   - Связать с CLI `/mode` командой.
2. **Корректировка ModelLimits для qwen3:14b** — context 40960 (реальный Ollama), а не 128K дефолт. Влияет на OutputBudget (более точный max_tokens).
3. **Prompt-оптимизация для local** — локальные 14B модели хуже следуют сложным инструкциям, чем cloud 200B. Адаптировать system prompt RAG-агента для local: более явные/короткие инструкции, явное требование формата.
4. **Benchmark-команда `/local bench`** — прогон набора запросов с замером before/after:
   - latency (ms), tokens/sec, RAM (из `ollama ps`), citation score.
   - Вывод: mordant-таблица before vs after по каждому параметру.
5. **Тесты** — новые пресеты, скорректированные ModelLimits, benchmark с mock.

## Deliverables

- [ ] Task-specific пресеты (RAG_LOCAL, CLASSIFY) с CLI `/mode`.
- [ ] ModelLimits для qwen3:14b скорректирован (context 40K).
- [ ] Prompt-адаптация для local (system prompt RAG-агента).
- [ ] Benchmark `/local bench`: before vs after — latency, tokens/sec, RAM, citation score.
- [ ] Документация: что оптимизировано, на сколько улучшилось.
- [ ] Тесты зелёные.
- [ ] Отчёт в `swarm-report/day-29-2026-07-11.md`.

## Архитектурные инварианты

- backward-compat: дефолтные пресеты (STANDARD/CONCISE) не меняются.
- ModelLimits change — additive (новая запись для qwen3:14b, не delete старой).
- Prompt-адаптация — conditional (только для local provider), cloud prompt не трогаем.
- Benchmark — read-only (не мутирует config), isolate runs.

## Связь с последующими днями

- **День 30** деплоит оптимизированную модель на VPS — настройки дня 29 применяются к VPS-инстансу.
- Benchmark дня 29 — основа для VPS-метрик дня 30.
