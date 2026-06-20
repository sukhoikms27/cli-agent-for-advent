# Дизайн: Стоимость после каждого сообщения (доп. п.6)

**Дата:** 2026-06-20
**Статус:** Draft (ожидает review)
**Тип:** Самостоятельное расширение (UX-polish; не входит в курс дня 15, отложено из декомпозиции)

## Контекст и мотивация

CLI-агент учитывает токены в `TokenCounter` (in-memory аккумулятор `SessionTokens`: `totalPrompt`/
`totalCompletion`/`lastRequestTokens`), но стоимость показывается только по `/cost` и `/stats`
(агрегат за сессию). Per-message стоимость не видна — пользователь не понимает, сколько стоил
каждый ответ, пока не спросит `/cost`.

Цель: после **каждого** ответа агента показывать строку вида `💰 $0.000123 (prompt=1234 completion=567)`.
Это даёт мгновенную обратную связь по затратам — важно для дорогих моделей (glm-5.1: $20/1M).

## Текущее состояние (из исследования)

- `ChatResponse.usage: Usage?` (`prompt_tokens`/`completion_tokens`/`total_tokens`) — есть,
  возвращается API, парсится kotlinx.serialization.
- `ContextAwareAgent.chat()` (строка 103–104) перехватывает `usage` и пишет в `tokenCounter.recordUsage`
  (in-memory, НЕ персистится).
- `Pricing.calculateCost(modelId, usage): Double?` — умеет считать per-request cost
  (`(promptTokens/1M)*input + (completionTokens/1M)*output`), требует фактический `Usage`.
- **Проблема:** `Agent.chat(): String` возвращает только контент. Usage «проглатывается» внутри
  `ContextAwareAgent`, не доходит до call-site (`ChatCommand`). Показать per-message cost невозможно
  без смены сигнатуры.

## Решения

1. **Ввести `ChatOutcome`** — результат чата, несущий контент + usage + cost. `Agent.chat()` возвращает
   `ChatOutcome` вместо `String`. Breaking change сигнатуры — каскад через все decorators.
2. **Персистентность суммарной стоимости** (опц., для п.7) — добавить аккумулятор в `ChatData`
   (`totalCost: Double = 0.0`), обновлять после каждого ответа. Переживает рестарт.
3. **Формат строки** — `💰 $0.000123 (prompt=1234 completion=567)`. Если pricing неизвестен для модели —
   только токены: `📊 prompt=1234 completion=567 (cost n/a)`.
4. **Opt-out** через `--no-cost-line` (для тех, кому мешает). Default on.

## Архитектура

### `ChatOutcome` (новый data class)

```kotlin
// llm/model/ChatOutcome.kt
data class ChatOutcome(
    val content: String,
    val usage: Usage? = null,
    val cost: Double? = null      // посчитан через Pricing; null если usage/pricing отсутствует
)
```

### Каскад сигнатуры `Agent.chat(): ChatOutcome`

`Agent` interface: `suspend fun chat(userMessage: String): ChatOutcome` (было `String`).
- `ContextAwareAgent.chat()` — уже считает usage; дополнительно `Pricing.calculateCost(model, usage)`;
  возвращает `ChatOutcome(content, usage, cost)`.
- `InvariantGuard.chat()` — возвращает `ChatOutcome`; usage/cost от делегата (guard не делает свой
  LLM-вызов при успехе; при retry — сумма? решение: usage последнего успешного ответа).
- `StatefulAgent.chat()` — делегирует `ChatOutcome` прозрачно.
- `TaskOrchestrator` `chat`-провайдер — тип `(String) -> ChatOutcome`; stage-агенты работают с
  `.content` (как сейчас), но orchestrator может суммировать cost stage-вызовов.

### Возврат к `String` где нужно

Stage-агенты (`StageAgent.run(ctx, chat: suspend (String) -> String)`) работают со строками — не
менять их сигнатуру. Оркестратор оборачивает: `{ msg -> statefulAgent.chat(msg).content }`.
Cost накапливается отдельно (orchestrator суммирует `outcome.cost` по stage-вызовам).

### Call-site в `ChatCommand`

```kotlin
val outcome = AppTerminal.withSpinner({ spinnerLabel(agent) }) { agent.chat(input) }
AppTerminal.println()
AppTerminal.markdown(outcome.content)
outcome.cost?.let { AppTerminal.println("💰 \$${"%.6f".format(it)} (prompt=${outcome.usage?.promptTokens ?: "?"} completion=${outcome.usage?.completionTokens ?: "?"})") }
AppTerminal.println()
```

### Персистентность суммарной стоимости (для п.7)

`ChatData.totalCost: Double = 0.0` (schema-evolution, default 0). `ContextAwareAgent.chat()` после
`recordUsage` обновляет аккумулятор:
```kotlin
outcome.cost?.let { memoryStore.updateTotalCost(chatId, it) }   // новый метод MemoryStore
```
Или через `ChatData` целиком (loadChat → copy(totalCost += cost) → saveChat). Schema-evolution:
старые чаты грузятся с `totalCost = 0.0`.

## Компоненты

| Компонент | Ответственность |
|---|---|
| `ChatOutcome` (новый) | DTO: content + usage + cost. |
| `Agent.chat()` (правка) | Возвращает `ChatOutcome` (было `String`). Каскад через decorators. |
| `ContextAwareAgent.chat()` (правка) | Считает cost через `Pricing`, возвращает `ChatOutcome`. |
| `InvariantGuard.chat()` (правка) | Делегирует `ChatOutcome`; usage/cost от последнего успешного ответа. |
| `StatefulAgent.chat()` (правка) | Прозрачно делегирует `ChatOutcome`. |
| `ChatCommand` call-sites (правка) | Пост-вывод строки стоимости. |
| `ChatData.totalCost` (правка, опц.) | Аккумулятор для п.7. |

## Риски

- **Breaking change сигнатуры `Agent.chat()`** — каскад через `InvariantGuard`, `StatefulAgent`,
  `TaskOrchestrator`, все тесты с mock `Agent` (`InvariantGuardTest`, `StatefulAgentTest`).
  Митигация: поэтапно; `.content` accessor упрощает миграцию (`outcome.content` вместо `outcome`).
- **Usage при retry-loop (InvariantGuard)** — несколько LLM-вызовов при Violated-ответе. Решение:
  cost последнего успешного ответа (не сумма retry — иначе неудачный retry удваивает счёт). Или сумма
  (честнее, но дороже выглядит). Решение на реализации: сумма (пользователь видит реальный расход).
- **`Pricing` без модели** — `calculateCost` возвращает `null` если `prices[modelId]` нет. Строка
  показывает только токены (`cost n/a`). Не падает.
- **In-memory vs персистентность** — сейчас `TokenCounter` in-memory (теряется при рестарте). Если
  персистить `ChatData.totalCost`, счётчик переживёт рестарт, но рассинхрон с `TokenCounter` (который
  обнуляется). Решение: для п.6 — in-memory достаточно (per-session); для п.7 — персистентность.

## Тестирование

- `ChatOutcome` data class: round-trip, defaults (usage/cost null).
- `ContextAwareAgent` тесты: `chat()` возвращает `ChatOutcome` с usage/cost (mock LlmClient с
  `Usage(100, 50, 150)` → cost через `Pricing`).
- `InvariantGuard` тесты: retry-loop — cost сумма (или последний) — обновить ассерты.
- `StatefulAgent` тесты: делегирование `ChatOutcome`.
- `ChatCommand` (если тестируется) — пост-вывод строки; `--no-cost-line` подавляет.
- Schema-evolution `ChatData.totalCost`: legacy JSON без поля → `0.0`.

## Вне скоупа

- Кэшированные токены (`promptTokensDetails.cachedTokens`) — отдельный учёт (скидка за cache).
  Будущее расширение.
- Cost по reasoning (если модель отдельно тарифицирует reasoning tokens).
- Budget limits (остановка при превышении $X) — отдельная фича.

## Критерии приёмки

- [ ] После каждого ответа — строка `💰 $0.000123 (prompt=… completion=…)`.
- [ ] Неизвестная модель → `📊 prompt=… completion=… (cost n/a)`.
- [ ] `--no-cost-line` подавляет строку.
- [ ] Cost при retry-loop корректно учтён (решение: сумма).
- [ ] `Agent.chat()` возвращает `ChatOutcome`; каскад через decorators; тесты обновлены.
- [ ] (опц.) `ChatData.totalCost` персистится, переживает рестарт.
- [ ] `./gradlew test build` зелёный.

## Зависимости

- `Agent` interface, `ContextAwareAgent`, `InvariantGuard`, `StatefulAgent`, `TaskOrchestrator`
  (каскад сигнатуры).
- `Pricing`, `Usage` (существующие).
- `ChatData`/`MemoryStore` (для опц. персистентности).
- Связан с п.7 (status-line cost использует тот же аккумулятор).
