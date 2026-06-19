# День 14. Инварианты и ограничения состояния — задачи

## Задание курса (`plan/newdays/day14.md`)
Добавить в ассистента инварианты, которые он не имеет права нарушать. Примеры: выбранная
архитектура, принятые технические решения, ограничения по стеку, бизнес-правила. Требования:
- инварианты хранятся **отдельно от диалога**;
- ассистент **явно учитывает** их в рассуждениях;
- ассистент **отказывается** предлагать решения, которые их нарушают.

Проверить: конфликт запроса и инварианта; как агент объясняет отказ.
Результат: ассистент, работающий в рамках заданных инвариантов.

## Место в курсе (Неделя 3 — третий столп)
Неделя 3 = три столпа: (1) Персонализация — `UserProfile` (День 12), (2) Task State Machine —
`TaskStateMachine` (День 13), (3) **Инварианты — этот день**. Плюс сборка StatefulAgent
(объединение профиля + состояния + инвариантов; закладывается здесь, полностью собирается в
следующих днях).

Конспекты недели 3 (`plan/videossummary/03-memory-state-*.md`):
- `03-memory-state-notes.md:373` — «интерфейс/абстрактный класс с функцией `check`».
- pipeline step 8 — «Валидируем ответ» (после LLM, до пользователя).
- step 9 — при нарушении retry/rewrite (агент получает feedback, переделывает), **не** просто refuse.
- `:383` — «линтер для требований, выраженных человеческим языком».
- Defense-in-depth: инварианты **и** в промпте, **и** в коде (после сжатия текстовые правила теряются).

## Принятые решения (по ответам пользователя)
1. **Стратегия проверки — LLM-as-judge** (семантическая). Stateless-утилитарный класс по образцу
   `ProfileExtractor` (день 12) — **не** отдельный агент (нет истории/контекста/стейта, один
   короткий LLM-вызов → парсинг). Ловит бизнес-правила/архитектуру, а не только keyword-совпадения.
2. **Модель judge'а — та же** `glm-5.1`, через тот же `LlmClient` (минимум новых сущностей).
3. **Проверка запроса + ответа** — запрос-нарушитель → отказ **без** основного LLM-вызова;
   ответ-нарушитель → retry-loop. Полностью покрывает day14 «отказывается» + «объясняет отказ».
4. **Хранение — отдельно от профиля** — `LongTermMemory.invariants` (global). Профиль
   (`UserProfile.constraints`) остаётся для персонализации (soft, промпт); инварианты — жёсткие
   правила проекта (hard, проверка кодом).

## Ключевая идея
Инварианты — жёсткие правила проекта (стек/запреты/архитектура/бизнес), отдельные от диалога и от
профиля пользователя. Хранятся в `LongTermMemory.invariants` (global, переживают restart).
Подмешиваются в system prompt через `PromptBuilder` (явный учёт в рассуждениях — defense-in-depth)
**и** проверяются программно через `InvariantChecker` (LLM-as-judge).

`InvariantGuard` (decorator поверх `Agent`) реализует оба направления проверки:
- **Запрос:** если `checkRequest` → Violated, агент возвращает отказ-объяснение **без** основного
  LLM-вызова (экономия токенов + жёсткое соблюдение).
- **Ответ:** `delegated.chat` → `checkResponse` → при Violated retry-loop с feedback (max 3) →
  fallback с предупреждением `⚠️`.

## Архитектура

### Слои
- `state/invariant/` (новый пакет): доменные модели (`Invariant`, `InvariantResult`),
  интерфейс `InvariantChecker`, реализация `LlmInvariantChecker`.
- `memory/` (правка): `LongTermMemory.invariants` — слот данных (эволюция схемы).
- `agent/` (правка + новый): `PromptBuilder` рендерит блок инвариантов; `ContextAwareAgent`
  получает аксессоры `getInvariants/setInvariants`; **новый `InvariantGuard`** (decorator).
- `cli/` (правка): `/invariants`-команды (по образцу `/profile`), wiring `InvariantGuard`,
  флаг `--invariants`.

### Пакетная диаграмма
```
cli/ChatCommand ────► agent/InvariantGuard ──► agent/ContextAwareAgent (delegated)
      │                     │ checkRequest         │ chat()
      │                     │ checkResponse        ▼
      │                     ▼                  llm/OpenAiCompatibleClient
      │              state/invariant/LlmInvariantChecker ──► (judge LLM-вызов, та же модель)
      │
      └─► /invariants → ContextAwareAgent.getInvariants/setInvariants → LongTermMemory.invariants
```

### Поток
```
userMessage → InvariantGuard.chat:
  checkRequest(msg, invariants)
    Violated → "⛔ Запрос нарушает инвариант: <rule>. <explanation>" (БЕЗ основного LLM-вызова)
    Valid    → delegated.chat(msg)
                 → checkResponse(resp, invariants)
                    Violated → delegated.chat(feedback: "нарушен <rule>, переделай") retry×3
                               → fallback: ответ + ⚠️ "не удалось соблюсти инвариант <rule>"
                    Valid    → resp
```

## Декомпозиция (выполнять последовательно, по подтверждению пользователя)

| # | Файл | Задача | Завис. |
|---|---|---|---|
| 01 | `state/invariant/Invariant.kt` | `Invariant(id, rule, category)` + enum `InvariantCategory {STACK, BAN, ARCH, BUSINESS}`, `@Serializable` | — |
| 02 | `state/invariant/InvariantResult.kt` | sealed `Valid` / `Violated(ruleId, rule, explanation)` | 01 |
| 03 | `state/invariant/InvariantChecker.kt` | interface: `checkRequest`, `checkResponse` | 01,02 |
| 04 | `state/invariant/LlmInvariantChecker.kt` | LLM-as-judge (паттерн ProfileExtractor): промпт→JSON, temp=0, fallback→Valid | 03 |
| 05 | `memory/MemoryLayer.kt` (правка) | `LongTermMemory.invariants: List<Invariant> = emptyList()` + `isEmpty()` | 01 |
| 06 | `agent/PromptBuilder.kt` (правка) | рендер `[Project invariants]` блока в system prompt | 05 |
| 07 | `agent/ContextAwareAgent.kt` (правка) | аксессоры `getInvariants()/setInvariants()` | 05 |
| 08 | `agent/InvariantGuard.kt` (новый) | decorator: checkRequest→отказ-без-LLM; checkResponse→retry(max 3)+fallback | 03 |
| 09 | `cli/ChatCommand.kt` (правка) | `/invariants show|add|remove|clear`; wiring guard при `--invariants`; help | 07,08 |
| 10 | `cli/ReplEngine.kt` (правка) | `/invariants` в completer | 09 |
| 11 | тесты (4 файла) | `InvariantTest`, `LlmInvariantCheckerTest`, `InvariantGuardTest`, +`ChatDataSchemaEvolutionTest` | 01–09 |
| 12 | верификация | `./gradlew test build` + manual smoke | 11 |

Артефакты: `01-invariant-model.md` … `12-verification.md`, `13-test-cases.md` (демо-кейс).

## Риски
1. **Retry-loop бесконечность** → max 3 попытки + fallback-ответ с `⚠️`.
2. **LLM-judge недетерминизм** → `temperature=0` + fallback-safe (ошибка LLM → `Valid`, не
   блокируем пользователя — лучше пропустить, чем зависнуть).
3. **Регресс 61 теста** → `--invariants` default **off**; поле `invariants` с дефолтом
   (forward-compat); checker через **decorator** (ContextAwareAgent почти не трогаем).
4. **Смешивание профиля и инвариантов** → намеренно разделяем: `UserProfile.constraints` =
   персонализация (soft, в промпте), `invariants` = жёсткие правила проекта (hard, проверка кодом).
5. **Токен-бюджет judge'а** → один короткий judge-промпт на запрос/ответ; учитывается в `TokenCounter`.

## Точки расширения
- Day 14+: `CompositeInvariantChecker` — несколько стратегий (keyword + LLM-judge) через
  композицию; сейчас только LLM-judge.
- Day 14+: инварианты per-chat (а не только global) — если бизнес-правила зависят от проекта.
- Интеграция с `ValidationStageAgent` — стадия validation может использовать `InvariantChecker`
  как часть своего вердикта.
- Авто-извлечение инвариантов из диалога (как `ProfileExtractor` для профиля) — extension point.

## Критерии готовности (соответствие заданию)
- ✅ Инварианты хранятся отдельно от диалога (`LongTermMemory.invariants`, global JSON).
- ✅ Ассистент явно учитывает их в рассуждениях (блок `[Project invariants]` в system prompt).
- ✅ Ассистент отказывается предлагать нарушающие решения (запрос-нарушитель → отказ без LLM;
  ответ-нарушитель → retry-loop + fallback).
- ✅ Проверка: конфликт запрос↔инвариант (отказ с объяснением), объяснение отказа (какой rule,
  почему нарушен).
- ✅ Без `--invariants` поведение = День 13 (нулевая регрессия, 61 тест зелёный).
