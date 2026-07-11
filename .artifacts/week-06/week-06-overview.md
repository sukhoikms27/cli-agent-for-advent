# Неделя 6 — Локальные LLM: Обзорный план недели

> Источник: `plan/videossummary/06_локальная_llm_summary.md` + `plan/newdays/day26-30.md`
> Дата начала: 2026-07-11

## Задания недели 6 (из видео-саммари)

1. ✅ Установить и запустить локальную LLM (Ollama + Qwen3 14B) → **день 26**
2. ⬜ Сравнить локальную и облачную LLM на одних запросах → **день 27-28**
3. ⬜ Применить MCP + RAG к локальной LLM → **день 28**
4. ⬜ (Бонус) Тумблер: переключение локальная↔облачная с маркировкой результатов → **день 27**

## Текущее состояние (из Research кодовой базы)

**Инфраструктура локальных LLM уже реализована в день 25:**
- `LlmProvider.OLLAMA` enum + `LlmClientFactory.create()` dispatch — `llm/LlmClientFactory.kt`
- `OpenAiCompatibleClient` шлёт auth-header только при непустом apiKey → Ollama без auth
- `ModelLimitsRegistry` знает qwen2.5/qwen3 → 128K/8K
- `OllamaEmbeddingClient` для RAG embeddings — `rag/embedding/OllamaEmbeddingClient.kt`
- RAG-пайплайн (Retriever/Indexer/Config) production-ready

**Ollama уже работает:**
- ollama v0.31.1, модель `qwen3:14b` (Q4_K_M, 14.8B, 40K context, tools+thinking)
- `nomic-embed-text:latest` для embeddings

**Вывод:** дни 26-30 — преимущественно верификация, интеграция, сравнение, оптимизация, деплой. Кодовые правки — минимальны и аддитивны.

## План по дням

| День | Тема | Основной deliverable | Ветка |
|---|---|---|---|
| **26** | Запуск локальной LLM | qwen3:14b отвечает через CLI, 3+ запроса, команда `/local` | `task/day-26` |
| **27** | Интеграция в приложение | Runtime live-switch local↔cloud + маркировка ответов (бонус-тумблер) | `task/day-27` |
| **28** | Локальная LLM + RAG | Полностью локальный RAG + `/rag compare-local` (local vs cloud) | `task/day-28` |
| **29** | Оптимизация локальной LLM | Task-specific пресеты, ModelLimits qwen3, prompt-адаптация, `/local bench` | `task/day-29` |
| **30** | Приватный сервис на VPS | Ollama на VPS + Caddy reverse proxy + HTTPS + auth | `task/day-30` |

## Стратегия ветвления

```
main
 └── swarm-dev (стабилизационная ветка, создана 2026-07-11)
      ├── task/day-26 (уже создана, пустая от main)
      ├── task/day-27 (от swarm-dev)
      ├── task/day-28 (от swarm-dev)
      ├── task/day-29 (от swarm-dev)
      └── task/day-30 (от swarm-dev)
```

- Каждый день разрабатывается в отдельной ветке `task/day-N`.
- Ветки ответвляются от `swarm-dev` (кроме day-26 — уже создана от main, идентична main).
- После завершения каждого дня — ветка вливается в `swarm-dev` для стабилизации.
- Каждый последующий день сверяется с предыдущими (история) и корректируется.

## Сквозная история (зависимости между днями)

```
День 26 (запуск + /local switch)
   ↓ live-switch используется
День 27 (runtime toggle + маркировка)
   ↓ тумблер для сравнения
День 28 (local RAG + compare-local)
   ↓ метрики как baseline
День 29 (оптимизация + bench)
   ↓ оптимизированная модель деплоится
День 30 (VPS приватный сервис)
```

**Важно:** при работе над каждым днем — обращаться к последующим черновикам и сверять ход всей истории. Корректировать последующие планы, не отходя от базовых требований.

## Workflow (по правилам оркестратора)

Каждый день проходит стадии: **Research → Plan → Executing → Validation → Report → Done**

- Research — консилиум/анализ экспертов
- Plan — план реализации
- Executing — написание кода (субагент)
- Validation — сборка, тесты, E2E-сценарий в `./artifacts/<slug>-e2e-scenario.md`
- Report — отчёт в `swarm-report/<slug>-<date>.md`
- Done — оркестратор фиксирует завершение

## Черновики планов

- [day-26-draft.md](./day-26-draft.md) — Запуск локальной LLM
- [day-27-draft.md](./day-27-draft.md) — Интеграция в приложение
- [day-28-draft.md](./day-28-draft.md) — Локальная LLM + RAG
- [day-29-draft.md](./day-29-draft.md) — Оптимизация
- [day-30-draft.md](./day-30-draft.md) — Приватный сервис на VPS (подробный, 796 строк)
