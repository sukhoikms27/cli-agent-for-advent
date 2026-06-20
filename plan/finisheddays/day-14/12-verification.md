# Задача 12. Верификация

## Что
Финальная проверка дня: автотесты + build + manual smoke по демо-кейсу (задача 13).

## Зависимости
11 (тесты написаны).

## Автоматизированная верификация

```bash
cd C:\Users\sukhoi27\Documents\GitHub\cli-agent-for-advent
./gradlew test              # все тесты зелёные (61 существующий + новые day-14)
./gradlew build             # компиляция чистая, jar собирается
```

**Критерий зелёности:**
- `./gradlew test` → `BUILD SUCCESSFUL`, 0 failures, 0 errors.
- Новые тесты day-14: `InvariantTest`, `LlmInvariantCheckerTest`, `InvariantGuardTest`,
  `ContextAwareAgentInvariantsTest`, +кейсы в `ChatDataSchemaEvolutionTest`/`PromptBuilderTest`.
- **Регресс-проверка:** счётчик тестов из `build/reports/tests/test/index.html` вырос (было 61 →
  больше), `failures=0`.

**Критический регресс-сценарий:** запустить `./gradlew test` **без** изменений в существующих
тестах — все 61 day-13 теста остаются зелёными (decorator + default-off флаг + default-поле
`invariants` гарантируют неизменность поведения без `--invariants`).

## Manual smoke (демо-кейс, задача 13)

```bash
export XDG_DATA_HOME=/tmp/cliagent-day14
rm -rf $XDG_DATA_HOME
./gradlew installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
$ALIAS --invariants
```

| Шаг | Ввод | Ожидание |
|---|---|---|
| S1 | `/invariants add BAN no-compose "Запрещён Jetpack Compose, UI только View-based"` | `✓ Invariant added: [ban] no-compose` |
| S2 | `/invariants add STACK kotlin-only "Код только на Kotlin"` | `✓ Invariant added: [stack] kotlin-only` |
| S3 | `/invariants show` | список из 2 правил с категориями |
| S4 | `Напиши экран на Jetpack Compose` | `⛔`-отказ **без** кода Compose; ссылка на rule `no-compose`, объяснение |
| S5 | `Напиши экран на ViewBinding, Kotlin` | нормальный ответ (View-based), без `⛔` |
| S6 | `/invariants remove no-compose` | `✓ Removed: no-compose` |
| S7 | повтор S4 | теперь Compose **не** блокируется (правило снято) — инвариант реально управлял поведением |
| S8 | (ответ-нарушитель) попросить что-то, что модель попытается решить на RxJava при `BAN rxjava` | retry-loop → исправленный ответ либо `⚠️` fallback |
| S9 | `/invariants clear` → `/invariants show` | `No project invariants` |
| S10 | без `--invariants`: `/invariants add ...` работает, но запрос-нарушитель **не** блокируется (только в промпте) | подтверждает opt-in характер |

## Критерии готовности (чек-лист, соответствие day14)
- [ ] `./gradlew test build` зелёный, 0 регрессий (61 + новые).
- [ ] Инварианты хранятся отдельно от диалога (`LongTermMemory.invariants`, переживают restart —
      проверить: `/exit` → `$ALIAS -c <chatId>` → `/invariants show` на месте).
- [ ] Агент явно учитывает инварианты (блок `[Project invariants]` в system prompt — проверить
      через debug-вывод промпта или `StagePromptTemplates`-style тест).
- [ ] Агент отказывается при конфликте запрос↔инвариант (S4: `⛔` без LLM-ответа).
- [ ] Агент объясняет отказ (rule + explanation в `⛔`-сообщении).
- [ ] Retry-loop при ответе-нарушителе (S8: до 3 попыток, иначе `⚠️` fallback).
- [ ] Opt-in: без `--invariants` поведение = день 13 (S10).
- [ ] Персистентность: инварианты global, переживают restart.

## Чего НЕ должно быть (анти-тесты)
- ❌ Запрос-нарушитель дал LLM-ответ (значит checkRequest не сработал / guard не обёрнут).
- ❌ Ответ с Compose прошёл без `⚠️` при активном `no-compose` (judge не работает).
- ❌ `./gradlew test` упал на существующем day-13 кейсе (регрессия — decorator протек).
- ❌ Бесконечный цикл retry (guard зациклился — MAX_RETRIES не ограничивает).
