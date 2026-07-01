# Стабилизация роя (день 21) — разъяснение и эффект

> Волны W1–W6: адаптивный рой, экономия токенов, точные промпты, эффективные tools, стабильность.
> До: рой **всегда и везде** (~31 LLM-вызов на тривиальную задачу). После: адаптивный гейт (~5–7).

## Что изменилось (карта решений)

### W1 — Стадийный гейт + режим роя (`SwarmMode`)
**`--swarm-mode auto|on|off`** (новый дефолт `auto`; `--no-swarm` — legacy alias → `off`).

| Режим | Поведение |
|---|---|
| `off` | простые агенты на всех стадиях (бывший `--no-swarm`) |
| `on`  | рой на всех стадиях (бывший дефолт; дебаг/сравнение) |
| `auto` | **адаптивно**: рой только на PLANNING/EXECUTION/VALIDATION; CLARIFY/DONE — single (W1.1+W1.2) |

`SwarmMode.kt` (enum), `TaskOrchestrator.defaultAgents(SwarmMode, complexity)`.

### W2 — Complexity-гейт (`TaskComplexity`)
`TaskComplexityClassifier` (1 LLM-вызов, temp=0): **TRIVIAL / MODERATE / COMPLEX**.

| Complexity | Рой (при `auto`) |
|---|---|
| TRIVIAL | **0 стадий** — весь пайплайн single-agent |
| MODERATE | PLANNING + EXECUTION + VALIDATION |
| COMPLEX | PLANNING + EXECUTION + VALIDATION |

Хранится в `TaskState.complexity`. Fallback при ошибке LLM → MODERATE.

### W3 — Экономия токенов
- **W3.1 Slim worker-context**: worker'у не нужны полные артефакты предшествующих стадий —
  только рамка (задача + короткая подсказка + профиль). Полный контекст остаётся в lead+integrate.
  `SwarmPrompts.workerContext()` (−~50–60% токенов на worker'ах).
- **W3.2 Per-stage tool-scoping**: tools (схемы ~1100 токенов) подключаются только на
  EXECUTION + VALIDATION. На CLARIFY/PLANNING/DONE — `tools=null`. При свободном чате (taskState==null)
  — старое поведение. `ContextAwareAgent.TOOL_SCOPED_STAGES`.
- **W3.3 Tool-aware промпты**: EXECUTION/VALIDATION поощряют использовать доступные tools
  (search/read/write) для актуальных данных вместо опоры на память. Обобщённо — без хардкода домена
  (агент универсальный). `StagePromptTemplates.toolAwareHint`.

### W4 — Качество
- **W4.1 VALIDATION → REDUNDANCY** (3 holistic проверки): раньше PARTITION по слайсам пропускал
  интеграционные дефекты. Теперь каждый worker проверяет **всю** реализацию, integrator сливает находки.
  `SwarmSpec.specFor(VALIDATION) = REDUNDANCY(3)`.
- **W4.2 Стратегия по TaskKind** (EXECUTION): CODE→PARTITION, REASONING→REDUNDANCY, WRITING→REDUNDANCY,
  EXPLANATION→SPECIALISTS. `SwarmSpec.specFor(stage, kind)`; `SwarmStageAgent` пересобирает spec из ctx.
- **W4.3 Lead PARTITION**: «покрытие полное (сумма частей = вся задача)» + «опиши контракты/интерфейсы
  между частями». Убирает дыры в декомпозиции и рассинхрон workers.

### W5 — Дедуп tool-вызовов (shared pre-fetch)
Перед fan-out workers — единый **research**-вызов собирает общие данные (через tools); результат
передаётся всем workers как `[Shared research]`. Гейт: только **EXECUTION + COMPLEX** (где tool-calls
вероятны и overhead оправдан). `SwarmPrompts.researchPrompt`, `SwarmStageAgent` shared-research.

### W6 — Стабильность + чистота
- **W6.1 withTimeout на worker** (90с): зависший worker → stub, не вешает стадию. `WORKER_TIMEOUT_MS`.
- **W6.2 Fallback integrator'а**: если integrate бросает/таймаутит — degraded-артефакт
  (конкатенация worker-выводов), стадия завершается.
- **W6.3 `--temperature` подключён**: ранее мёртвый флаг. Теперь прокидывается в `ChatRequest.temperature`
  основного цикла (default 0.7). Классификаторы/экстракторы остаются на `0.0` (детерминизм).

## Before/After — число LLM-вызовов и токенов

| Сценарий | Было (рой везде) | После W1+W2 | После всех волн |
|---|---|---|---|
| Тривиальная задача | ~31 вызов | ~5 (single-pipeline) | ~5 |
| COMPLEX задача | ~31 вызов | ~17 | ~18 (+shared research) |
| Контекст на worker | полный (×3) | полный | **slim (×~2.5)** |
| Tools-схемы в CLARIFY/PLAN/DONE | ~1100 ток | ~1100 ток | **0** |
| VALIDATION покрытие дефектов | слайсы (пропуски) | слайсы | **holistic** |
| Дублирующие tool-calls в рое | до 5× | до 5× | **1× (shared)** |

## Карта потока (актуальная)

```
CLI: --swarm-mode auto (default) + --temperature
        │
        ▼
TaskOrchestrator.startTask
   ├─ TaskComplexityClassifier (1 вызов, temp=0) → TRIVIAL/MODERATE/COMPLEX
   ├─ defaultAgents(auto, complexity) → stage→agent карта (swarm only where worth it)
   └─ drive(stage) → runOneStage → agents[stage].run(ctx)
                                          │
                    ┌─────────────────────┴──── если SwarmStageAgent ─────────┐
                    ▼                                                      │
        lead (декомпозиция: PARTITION/SPECIALISTS/REDUNDANCY)               │
                    │                                                      │
                    ▼                                                      │
        [W5] shared research (EXECUTION+COMPLEX только) → [Shared research] │
                    │                                                      │
                    ▼                                                      │
        workers (async, [W6.1] withTimeout, slim ctx + shared)              │
                    │                                                      │
                    ▼                                                      │
        integrate ([W6.2] fallback → degraded если упал)                    │
                    └──────────────────────────────────────────────────────┘
                                          │
                                          ▼
        StageResult(артефакт) → TaskState → ворота → след.стадия
```

## Файлы (W1–W6)
- `agent/swarm/SwarmMode.kt` (W1.3) — новый enum режимов роя
- `state/TaskComplexity.kt` (W2) — новый enum сложности
- `agent/stage/TaskComplexityClassifier.kt` (W2.1) — классификатор
- `agent/stage/TaskOrchestrator.kt` (W1.1/W1.2/W2.2) — `defaultAgents(SwarmMode, complexity)`, startTask
- `agent/swarm/SwarmPrompts.kt` (W3.1/W4.3/W5.1) — slim workerContext, researchPrompt, lead-контракты
- `agent/swarm/SwarmStrategy.kt` (W4.1/W4.2) — VALIDATION→REDUNDANCY, specFor(stage, kind)
- `agent/swarm/SwarmStageAgent.kt` (W4.2/W5.1/W6.1/W6.2) — effectiveSpec, shared research, timeout, fallback
- `agent/ContextAwareAgent.kt` (W3.2/W6.3) — TOOL_SCOPED_STAGES, temperature
- `llm/model/StagePromptTemplates.kt` (W3.3) — tool-aware hint (EXECUTION/VALIDATION)
- `cli/ChatCommand.kt` (W1.3/W6.3) — --swarm-mode, --temperature wiring
- `state/TaskState.kt` (W2) — complexity field
- `agent/stage/StageAgent.kt` (W5) — StageContext.complexity

## Тесты
- `SwarmModeGateTest` (7) — гейты OFF/ON/AUTO + TRIVIAL + legacy-перегрузка + fromString
- `TaskComplexityClassifierTest` (7) — TRIVIAL/MODERATE/COMPLEX + fallback + blank
- Существующие `SwarmStageAgentTest`/`SwarmStrategyTest` — green (0 регрессий)
