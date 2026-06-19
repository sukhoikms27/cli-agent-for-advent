# Задача 13. Кейс тестирования инвариантов (Day 14) — демо

Пошаговый кейс для верификации задания Day 14 (инварианты и ограничения состояния). Привязан к
реальной реализации: `Invariant`, `InvariantResult`, `InvariantChecker`, `LlmInvariantChecker`,
`InvariantGuard`, `LongTermMemory.invariants`, `PromptBuilder` (блок `[Project invariants]`),
`/invariants`-команды, флаг `--invariants`.

## Контекст
Сениор Android-разработчик, View-based стек. Инварианты — жёсткие правила проекта (стек/запреты/
архитектура), отдельные от диалога и от профиля. Проверяются LLM-as-judge (stateless-утилита,
та же модель glm-5.1) в двух направлениях: запрос → отказ-без-LLM; ответ → retry-loop.

**Задача-пример для демо (без написания кода/файлов):** техническое проектирование нового модуля
приложения — агент выступает **архитектором-консультантом**. Пользователь спрашивает совета о
выборе технологий, паттернов, библиотек, навигации, организации слоя состояния. Инварианты
ограничивают именно *рекомендации и предлагаемые решения*, а не генерацию кода. Это точно
соответствует формулировке курса: ассистент «отказывается **предлагать решения**, которые их
нарушают».

> Почему не задача с генерацией кода: файловые операции вынесены за рамки текущего этапа.
> Архитектурный консалтинг полностью раскрывает суть day14 — инварианты как критерии отбора
> решений — без необходимости что-либо создавать на диске.

## Предусловие — инварианты проекта (воспроизводимо через REPL)
```text
cli-agent> /invariants add BAN no-compose "UI только View-based: запрещён Jetpack Compose, запрещено рекомендовать setContent{}"
cli-agent> /invariants add BAN no-xml "Запрещены XML-разметки; UI рекомендовать только через ViewBinding или программную сборку"
cli-agent> /invariants add STACK kotlin-only "Код только на Kotlin, запрещено рекомендовать Java-классы/библиотеки без Kotlin API"
cli-agent> /invariants add ARCH mvi-only "Архитектура строго MVI (Intent/State/Reducer/ViewModel); не предлагать MVP/MVC/MVVM"
cli-agent> /invariants show
```
Ожидание `/invariants show`:
```text
🔒 Project invariants:
  [arch] mvi-only: Архитектура строго MVI (Intent/State/Reducer/ViewModel) ...
  [ban] no-compose: UI только View-based: запрещён Jetpack Compose ...
  [ban] no-xml: Запрещены XML-разметки ...
  [stack] kotlin-only: Код только на Kotlin ...
```

## Подготовка
```bash
export XDG_DATA_HOME=/tmp/cliagent-day14-demo
rm -rf $XDG_DATA_HOME
./gradlew test build          # 61 + новые day-14 тесты зелёные
./gradlew installDist
ALIAS="build/install/cli-agent/bin/cli-agent chat"
```
> Проверка отказа запроса и retry ответа **требует** `--invariants` (без него инварианты только в
> промпте, программно не enforced). Управление списком (`/invariants add/show/...`) работает без флага.

## Что проверяем (маппинг на задание курса)
1. Инварианты хранятся отдельно от диалога (`LongTermMemory.invariants`, global).
2. Агент явно учитывает их в рассуждениях (блок `[Project invariants]` в system prompt).
3. **Отказ при конфликте запрос↔инвариант** — запрос-нарушитель блокируется без LLM-ответа.
4. **Объяснение отказа** — агент указывает, какой rule нарушен и почему.
5. Retry-loop при ответе-нарушителе (модель попыталась нарушить → переделывает).
6. Управление списком (`add/remove/clear`).
7. Персистентность (переживают restart).
8. Opt-in: без `--invariants` поведение = день 13.

---

## Сценарий A. Отказ при конфликте запрос↔инвариант (ядро day14)

### A1. Запрос прямо просит запрещённое решение
```text
cli-agent> Хочу перевести UI приложения на Jetpack Compose для современности. Посоветуй, с чего начать миграцию.
```
Ожидание:
- `⛔`-отказ. **Без** плана миграции на Compose (checkRequest → Violated → отказ без `delegated.chat`).
- Сообщение содержит: rule (`no-compose`), текст правила, explanation («запрос просит Compose,
  который запрещён инвариантом проекта»).
- В логе LLM-вызовов — **нет** основного чата (экономия токенов).

### A2. Корректный запрос проходит
```text
cli-agent> Посоветуй, как организовать UI нового модуля в View-based стеке с ViewBinding.
```
Ожидание: нормальный ответ — рекомендация ViewBinding/программной сборки на Kotlin, **без** Compose/XML.
`⛔` отсутствует. Контраст A1↔A2 доказывает, что блокирует именно инвариант, а не общая придирчивость.

### A3. Запрос косвенно ведёт к нарушению
```text
cli-agent> Какой подход к UI сейчас считается самым современным на Android?
```
Ожидание: агент упоминает Compose как «современный», но **отмечает**, что проект запрещает его
(инвариант `no-compose` в system prompt), и рекомендует View-based/ViewBinding. Если судья
посчитает рекомендацию Compose нарушающей → retry-loop → исправленный ответ.

---

## Сценарий B. Retry-loop при ответе-нарушителе

### B1. Модель попыталась рекомендовать запрещённый стек
```text
cli-agent> Как лучше организовать реактивную обработку событий в новом модуле?
```
Ожидание (при инварианте `ARCH mvi-only` и условном `BAN rxjava`):
- Агент рекомендует RxJava → судья видит нарушение → `Violated` → feedback-промпт → перегенерация
  с рекомендацией StateFlow/Flow в рамках MVI, без RxJava.
- Финальный ответ: реактивность через Kotlin Flow/StateFlow, MVI.

### B2. Не удалось исправить за 3 попытки → fallback
Воспроизводимо через unit-тест `InvariantGuardTest` (checker всегда Violated → 4 вызова delegated →
fallback с `⚠️` и «MAX_RETRIES»). В REPL — только если реальная модель упрямится. Ожидание финала:
`⚠️ ... не удалось соблюсти инварианты ... Последний ответ (требует ручной проверки): ...`.

---

## Сценарий C. Управление списком инвариантов

### C1. Добавить / показать
```text
cli-agent> /invariants add BUSINESS free-api "Рекомендовать только бесплатные библиотеки и API"
cli-agent> /invariants show
```
Ожидание: в списке появился `[business] free-api`. Категория `BUSINESS` отображается.

### C2. Обновить по id (не дублировать)
```text
cli-agent> /invariants add STACK kotlin-only "Kotlin 2.0+, никаких Java-классов в рекомендациях"
cli-agent> /invariants show
```
Ожидание: `kotlin-only` — **одна** запись, с обновлённым текстом «Kotlin 2.0+» (не две).

### C3. Удалить и проверить, что блокировка снимается
```text
cli-agent> /invariants remove no-compose
cli-agent> повтори запрос из A1 (миграция на Compose)
```
Ожидание: `✓ Removed: no-compose`; запрос про Compose теперь **не блокируется** `⛔` (правила нет) —
агент может дать рекомендацию. Это доказывает, что блокировку делал именно `no-compose`.

### C4. Удалить несуществующий
```text
cli-agent> /invariants remove zzz
```
Ожидание: `⚠️ Invariant not found: zzz`.

### C5. Очистить всё
```text
cli-agent> /invariants clear
cli-agent> /invariants show
```
Ожидание: `No project invariants. Use: /invariants add ...`.

---

## Сценарий D. Персистентность (переживают restart)

```text
cli-agent> /invariants add BAN no-compose "запрещён Compose"
/exit
```
```bash
CHATID=$(ls $XDG_DATA_HOME/chats/*.json | head -1 | xargs basename | sed 's/.json//')
$ALIAS --invariants -c "$CHATID"
```
```text
cli-agent> /invariants show
```
Ожидание: `no-compose` на месте (global `LongTermMemory.invariants`, не per-chat). Повтор A1
(запрос про Compose) → снова `⛔` (guard восстановлен после restart).

---

## Сценарий E. Opt-in: без `--invariants` (обратная совместимость)

```bash
$ALIAS   # БЕЗ --invariants
```
```text
cli-agent> /invariants add BAN no-compose "запрещён Compose"
cli-agent> Посоветуй миграцию на Jetpack Compose
```
Ожидание: список инвариантов сохранён (`/invariants show` работает), но запрос про Compose **не**
блокируется `⛔` (программная проверка выключена). Инвариант лишь в промпте — модель может
соблюсти его «по доброй воле», но жёсткой защиты нет. Доказывает opt-in характер и нулевую
регрессию относительно дня 13.

---

## Сценарий F. Явный учёт в рассуждениях (defense-in-depth слой 1)

Проверить, что блок `[Project invariants]` попадает в system prompt:
- Через debug/trace вывод промпта (если есть флаг), либо
- Юнит-тест `PromptBuilderTest` (задача 11): `LongTermMemory(invariants=[Invariant("no-compose",
  "...", BAN)])` → `build().content` содержит `[Project invariants]` + `MUST NOT` + текст правила.

Контраст: `LongTermMemory()` (без инвариантов) → блок отсутствует (ноль регресса).

> Негативный сигнал: если в рекомендации на корректный View-based запрос появляется совет
> использовать XML-разметку или `setContent{}` при активных `no-xml`/`no-compose` → либо судья
> не сработал (retry-loop не запустился), либо инвариант не попал в промпт.

---

## Сценарий G. Автоматизированные тесты (`./gradlew test`)

| Тест-класс | Что проверяет | Пример данных |
|---|---|---|
| `InvariantTest` | round-trip JSON; `InvariantCategory` сериализация; `InvariantResult.Valid`/`Violated` equality | `Invariant("no-compose","...",BAN)` encode→decode |
| `LlmInvariantCheckerTest` | judge: Violated/Valid по JSON; `LlmResult.Error`→Valid (fallback); пустой список→без вызова; мусор→Valid; temp=0; разные промпты request/response | mock `{"violated":true,"ruleId":"no-compose","explanation":"..."}` → `Violated` |
| `InvariantGuardTest` | пустой список→fast-path; request Violated→без delegated.chat; response Valid→1 вызов; всегда Violated→4 вызова+fallback; Violated→Valid→2 вызова | mock delegated (`returnsChained`) + mock checker |
| `ChatDataSchemaEvolutionTest` (+3) | legacy LongTermMemory без `invariants`→`[]`; round-trip с invariants; `isEmpty()`=false при инварианте | `{"knowledge":{},"decisions":{}}` → `invariants==emptyList()` |
| `PromptBuilderTest` (+2) | блок `[Project invariants]` рендерится/отсутствует; содержит `MUST NOT` | `LongTermMemory(invariants=[Invariant(...)])` |
| `ContextAwareAgentInvariantsTest` | set/add/remove round-trip; обновление по id; remove("zzz")→false; не затирает profile | mock MemoryStore, slot capture |

---

## Чек-лист приёмки (соответствие заданию курса)
- [x] Инварианты хранятся отдельно от диалога — `LongTermMemory.invariants` (global JSON).
- [x] Агент явно учитывает их в рассуждениях — блок `[Project invariants]` в system prompt (F).
- [x] Агент отказывается предлагать нарушающие решения — запрос `⛔` без LLM (A1), ответ retry (B).
- [x] Проверка конфликта запрос↔инвариант — A1 (`⛔`), C3 (после remove — не блокируется).
- [x] Объяснение отказа — `⛔` содержит rule + explanation (A1).
- [x] Управление списком — C1–C5.
- [x] Персистентность — D.
- [x] Opt-in / нулевая регрессия — E (без `--invariants` = день 13).
- [x] Демо не требует файловых операций — задача-пример = архитектурный консалтинг (рекомендации,
      выбор технологий), артефакт — совет/оценка, а не код в файл.

## Зависимости
Задача 12 (верификация). Расширяет демо day-13 (`plan/finisheddays/day-13/13-test-cases.md`)
третьим столпом недели 3 — инвариантами. Реализация: `com.cliagent.state.invariant.*`,
`agent/InvariantGuard.kt`, правки `MemoryLayer`/`PromptBuilder`/`ContextAwareAgent`/`ChatCommand`.
