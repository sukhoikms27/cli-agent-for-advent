# День 26 — Запуск локальной LLM (ЧЕРНОВИК плана)

> Неделя 6, задание 1 из `plan/newdays/day26.md` + `videossummary/06_локальная_llm_summary.md`

## Задание

🔥 **День 26. Запуск локальной LLM**

Установите и запустите любую локальную LLM (Ollama, LM Studio, llama.cpp, Mistral, Qwen, Phi и т.п.)
- 👉 модель запускается локально
- 👉 к ней можно обратиться через CLI или HTTP API
- 👉 модель отвечает на простой запрос

Сделайте: минимум 3 запроса разной сложности.

**Результат:** Локальная LLM запущена и отвечает на запросы.

Видео-саммари прямо отмечает: «Установить и запустить локальную LLM (Ollama + Qwen3 14B) → **task26**».

## Контекст кодовой базы (из Research)

- **Multi-provider уже реализован в день 25**: `LlmProvider.OLLAMA` enum, `LlmClientFactory.create()` dispatch, `OpenAiCompatibleClient` шлёт auth-header только при непустом `apiKey` → Ollama работает без auth.
- **`ModelLimitsRegistry`** (`llm/ModelLimits.kt:22-54`) уже знает `qwen3` → 128K context / 8K output.
- **Env-переменные**: `CLI_AGENT_PROVIDER=ollama`, `CLI_AGENT_BASE_URL=http://localhost:11434/v1`, `CLI_AGENT_MODEL=qwen3:14b`.
- **Ollama уже установлена** (v0.31.1), модель `qwen3:14b` (Q4_K_M, 14.8B, 40K context, tools+thinking capabilities) скачана и отвечает.

**Вывод:** день 26 — преимущественно верификация + кодовые правки минимальны. Основной deliverable — прогон 3+ запросов разной сложности + документация результата.

## Что добавить в код (минимально)

1. **Команда `/local`** (или `/provider`) — быстрый переключатель на локальную модель из REPL, без правки env/config и рестарта. Сейчас `/config set provider ollama` требует restart chat. Live-switch — хорошее дополнение (см. `plan/extensions/08-runtime-chat-switching.md`).
2. **Smoke-тест** — проверка доступности локальной модели (`/local status` / `/provider status`): пинг `GET /api/tags`, проверка что модель скачана.
3. **Логирование результата 3 запросов** в artifacts.

## Deliverables

- [ ] Локальная модель `qwen3:14b` отвечает через CLI-агента (`CLI_AGENT_PROVIDER=ollama`).
- [ ] Минимум 3 запроса разной сложности (simple / medium / hard) задокументированы.
- [ ] (Опционально) команда `/local` для live-переключения.
- [ ] Отчёт в `swarm-report/day-26-2026-07-11.md`.

## Архитектурные инварианты

- env > config.json (конвенция AGENTS.md).
- `LlmProvider.OLLAMA.requiresApiKey() == false` — не требовать API key.
- Backward-compat: cloud (zai) работает как раньше.
- Корутины: `withContext(Dispatchers.IO)` для HTTP.

## Связь с последующими днями

- **День 27** использует live-switch дня 26 (если добавлен) для бесшовной интеграции.
- **День 28** использует локальную модель для RAG-генерации.
