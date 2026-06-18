# Задача 08. Кейс тестирования персонализации (Day 12)

Пошаговый тест-кейс для верификации задания Day 12 (персонализация ассистента).
Привязан к реальной реализации: `UserProfile`, `ProfileExtractor`, `PromptBuilder.renderBlock`,
`ContextAwareAgent` (`getProfile/setProfile`, `--auto-profile`), `handleProfile` в `ChatCommand`.

## Что проверяем (маппинг на задание курса)

1. Профиль пользователя создан (`UserProfile`).
2. Описаны предпочтения (style/format/about/constraints).
3. Профиль подключён к каждому запросу (`PromptBuilder` → system prompt).
4. Ответы для разных профилей — разные.
5. Ассистент учитывает профиль автоматически (`/profile extract`, `--auto-profile`).
6. Профиль переживает рестарт (global long-term).
7. Эволюция схемы (старый JSON без `about` грузится).
8. Инвариант совместимости (без `--auto-profile` поведение = Day 11, нет доп. LLM-вызовов).

## Подготовка (общая)

```bash
export CLI_AGENT_API_KEY=Bearer token
export CLI_AGENT_MODEL=glm-5.1
export XDG_DATA_HOME=/tmp/cliagent-day12-test
rm -rf $XDG_DATA_HOME

./gradlew build           # сборка + юнит-тесты Day 12
./gradlew test            # отдельно прогон тестов
```

Чистый `XDG_DATA_HOME` нужен, чтобы старый long-term профиль не влиял на сценарии.

---

## Сценарий A. Разные профили → разные ответы (общий)

**Цель:** доказать, что один и тот же запрос даёт разный результат при разных профилях.
Зонд: `напиши сервис авторизации`.

### A1. Профиль «краткий бэкендер на Ktor»

```text
cli-agent> /profile set style concise
cli-agent> /profile set format с примерами кода на Kotlin
cli-agent> /profile set about backend dev, стек Ktor + Exposed
cli-agent> /profile add constraint no RxJava
cli-agent> /profile add constraint Kotlin only
cli-agent> /profile show
```

Ожидаемый `/profile show`:
```text
👤 User profile:
  Style: concise
  Format: с примерами кода на Kotlin
  About: backend dev, стек Ktor + Exposed
  Constraints:
    - no RxJava
    - Kotlin only
```

Ожидания по ответу на зонд:
- Ответ короткий (1–2 блока кода, минимум воды).
- Код на Kotlin, используется Ktor routing / Exposed.
- В тексте/коде нет RxJava/RxKotlin, нет Java-стиля (`CompletableFuture`).

### A2. Смена профиля на «подробный, для новичка, Python»

```text
cli-agent> /profile clear
cli-agent> /profile set style verbose с пояснениями для новичка
cli-agent> /profile set about студент, учу бэкенд, знаю Python
cli-agent> /profile add constraint без Kotlin
cli-agent> /profile show
```

Тот же зонд `напиши сервис авторизации`.

Ожидания:
- Ответ развёрнутый, с объяснениями «почему так».
- Язык реализации — Python (Flask/FastAPI), нет Kotlin.
- Тон — обучающий.

### A3. Контрастная проверка (ядро задания «ответы для разных профилей»)

| Ось | A1 (Ktor/concise) | A2 (Python/verbose) |
|---|---|---|
| Длина | короткий | развёрнутый |
| Язык кода | Kotlin | Python |
| Запрет | нет RxJava | нет Kotlin |

> Если оба ответа похожи — профиль не подмешивается. Это главный негативный сценарий.

---

## Сценарий B. Профиль применяется автоматически к каждому запросу

**Цель:** убедиться, что `PromptBuilder` вставляет профиль в system prompt **каждого** запроса,
а не только первого.

1. Поставь профиль из A1.
2. Сделай 3–4 хода разными запросами:
   ```text
   cli-agent> что такое JWT
   cli-agent> как хранить пароли
   cli-agent> покажи middleware для логирования
   ```
3. После каждого хода проверяй: ответ остаётся в рамках constraints (Kotlin, кратко, без RxJava).

Жёсткая проверка (юнит-уровень, см. Сценарий G): `ContextAwareAgentProfileTest` — непустой профиль
в `memoryStoreMock` → `messages[0].content` (system prompt) содержит `User profile:` и значения
style/about/constraints. Это и есть доказательство автоматической инъекции.

---

## Сценарий C. `/profile extract` — авто-извлечение из диалога (общий)

### C1. Подготовка диалога с явными предпочтениями

```text
cli-agent> /profile clear
cli-agent> я пишу на Kotlin, предпочитаю короткие ответы без лишних объяснений
cli-agent> не используй RxJava в коде, только корутины
cli-agent> я бэкенд-разработчик, делаю REST API на Ktor
```

### C2. Извлечение

```text
cli-agent> /profile extract
```

Ожидание: спиннер `Inferring profile…`, затем `Profile inferred and merged`.

### C3. Проверка результата

```text
cli-agent> /profile show
```

LLM вернёт текст строго в формате, который парсит `parseProfile`:
```text
style: кратко
format:
about: бэкенд-разработчик, Kotlin, Ktor, REST API
constraints:
- no RxJava
- только корутины
```

Ожидаемые распарсенные поля:
- `style` ≠ null (краткий/без объяснений).
- `about` ≠ null (бэкенд/Kotlin/Ktor).
- `constraints` содержит запрет на RxJava.

### Вариант C4. `extract` на пустом диалоге

```text
cli-agent> /reset
cli-agent> /profile extract
```
Ожидание: `No dialog yet to infer profile from.` (history пуста → ранний return).

### Вариант C5. `extract` при ошибке LLM

Проверяется юнит-тестом `ProfileExtractorTest`: mock возвращает `LlmResult.Error` → `extract`
возвращает `current` без изменений. В REPL эмулируется плохим `CLI_AGENT_BASE_URL`.

---

## Сценарий D. `--auto-profile` — авто-извлечение каждые 5 ходов (общий)

```bash
cli-agent chat --auto-profile
```

```text
cli-agent> /profile clear
cli-agent> я пишу на Python, люблю подробные ответы с теорией
cli-agent> не используй Django, только FastAPI
cli-agent> я учусь, цель — понять как работает auth
cli-agent> объясни JWT
# 5-й ход (turnCount % 5 == 0) — должен сработать авто-extract
cli-agent> чем access token отличается от refresh
```

Ожидание: после 5-го хода профиль автоматически наполнен (`/profile show` показывает inferred
style/about/constraints), хотя `/profile extract` вручную не вызывали.

> `turnCount` инкрементится в `chat()` после ответа ассистента. Считай только запросы к модели,
> а не `/profile`-команды (они идут через `handleProfile`, а не `chat()`).

### Вариант D1 — авто выключен (инвариант совместимости)

```bash
cli-agent chat     # без --auto-profile
```

Поведение идентично Day 11: `profileExtractor = null`, `autoProfileEvery = 0` → ни одного доп.
LLM-вызова на извлечение. После 5+ ходов `/profile show` остаётся пустым (если не ставили вручную).

---

## Сценарий E. Персистентность / рестарт (global long-term)

```text
# сессия 1
cli-agent> /profile set style concise
cli-agent> /profile set about backend dev
cli-agent> /profile add constraint Kotlin only
cli-agent> /exit     # или Ctrl+D
```

```bash
cat $XDG_DATA_HOME/cli-agent/long-term.json
```

Ожидаемое содержимое (схема Day 12):
```json
{"profile":{"style":"concise","about":"backend dev","constraints":["Kotlin only"]}}
```

```text
# сессия 2 — новый чат
cli-agent> /profile show
```

Ожидание: профиль на месте (style/about/constraints).

```text
cli-agent> напиши hello world endpoint
```

Ожидание: ответ на Kotlin, краткий — профиль применился в **новом** чате автоматически (global,
не per-chat).

---

## Сценарий F. Граничные/негативные кейсы `/profile`

| # | Ввод | Ожидание |
|---|---|---|
| F1 | `/profile` (без аргументов) на пустом профиле | `👤 User profile is empty. Use: /profile set ...` |
| F2 | `/profile set style` (без текста) | `Usage: /profile set <style\|format\|about> <text>` |
| F3 | `/profile set foo bar` (неизвестное поле) | `Unknown field: foo. Use: style, format, about` |
| F4 | `/profile add` (без constraint) | `Usage: /profile add constraint <text>` |
| F5 | `/profile remove constraint RxJava` когда такого нет | `No matching constraint found.` |
| F6 | **`remove` по частичному совпадению** (риск из плана): профиль `["no RxJava", "no RxJava in tests"]`, команда `/profile remove constraint RxJava` → `contains` fallback удалит **оба**. Зафиксировать как известное поведение (или баг к починению Day 13). |
| F7 | `/profile add constraint "no RxJava"` повторно дважды | добавится дубликат (CLI не делает distinct — в отличие от `mergeProfile`). Зафиксировать отличие от merge. |
| F8 | `/profile clear` → `/profile show` | `👤 User profile is empty...` |

> F6 и F7 — edge-кейсы, где поведение отличается от интуитивного; прогонять явно.

---

## Сценарий G. Автоматизированные тесты (`./gradlew test`)

Соответствие плану `06-tests.md`:

| Тест-класс | Что проверяет | Пример данных |
|---|---|---|
| `PromptBuilderTest` | профиль с `about` рендерится → в system prompt есть `User profile:` и `About: backend dev` | `UserProfile(style="concise", about="backend dev", constraints=listOf("no RxJava"))` |
| `ProfileExtractorTest` | mock `LlmClient` → текст-шаблон парсится в style/format/about/constraints; `LlmResult.Error` → возвращается `current`; `mergeProfile` сохраняет старые поля при пустых inferred + аккумулирует constraints с distinct | входной текст `style: кратко\nabout: b\nconstraints:\n- no RxJava` |
| `ContextAwareAgentProfileTest` | (a) непустой профиль → `messages[0].content` содержит `User profile`; (b) `autoProfileEvery=2` + mock extractor → после 2 `chat()` профиль обновлён и персистён | spy `ProfileExtractor` возвращает `UserProfile(about="inferred")` |
| `UserProfileSchemaTest` | round-trip `UserProfile(about="x")`; старый JSON без `about` → `about == null` (эволюция схемы) | `{"style":"concise"}` → `about == null` |

Ключевой merge-кейс (для `ProfileExtractorTest`):
```kotlin
mergeProfile(
    UserProfile(style = "a", constraints = listOf("c1")),
    UserProfile(about = "b", constraints = listOf("c1", "c2"))
)
// → style="a", about="b", constraints=["c1","c1","c2"].distinct() = ["c1","c2"]
```

---

# Android-адаптация: View-based vs Jetpack Compose

Контрастные сценарии под Android-разработку. «Разные профили → разные ответы» проверяется на
двух реальных парадигмах UI: **View-based (XML)** и **Jetpack Compose** — максимальный контраст
по стеку, коду и архитектуре, идеальный зонд для Day 12.

Ниже — изменившиеся сценарии (A, C, D, E с Android-данными); B, F, G из общего блока без изменений.

## Зонд-запрос (общий для Android-сценариев)

```text
напиши экран списка элементов с кликом по элементу
```

На View-based и на Compose даёт принципиально разную структуру кода — легко отличить визуально.

## Сценарий A-Android. View-based vs Compose → разные ответы

### A1-Android. Профиль «Android, View-based (XML)»

```text
cli-agent> /profile set style concise
cli-agent> /profile set format XML layout + Kotlin, ViewBinding
cli-agent> /profile set about Android dev, стек View-based (Activities/Fragments, XML, ViewBinding, RecyclerView)
cli-agent> /profile add constraint no Jetpack Compose
cli-agent> /profile add constraint ViewBinding для доступа к view
cli-agent> /profile add constraint Kotlin only
cli-agent> /profile show
```

Ожидаемый `/profile show`:
```text
👤 User profile:
  Style: concise
  Format: XML layout + Kotlin, ViewBinding
  About: Android dev, стек View-based (Activities/Fragments, XML, ViewBinding, RecyclerView)
  Constraints:
    - no Jetpack Compose
    - ViewBinding для доступа к view
    - Kotlin only
```

Зонд `напиши экран списка элементов с кликом по элементу`.

Ожидания (критерии успеха):
- Есть XML-разметка (`<androidx.recyclerview.widget.RecyclerView>` в `activity_list.xml` или `fragment_list.xml`).
- Kotlin-код использует `ViewBinding` (`ActivityListBinding`, `binding.recyclerView`), **не** `findViewById`.
- `RecyclerView.Adapter<ItemViewHolder>` + `ViewHolder` с кликом (`adapterPosition` / `setOnClickListener`).
- Activity или Fragment как контейнер.
- **Нет** `@Composable`, нет Compose-импортов (`androidx.compose.*`).
- Краткий стиль, минимум теории.

### A2-Android. Профиль «Android, Jetpack Compose»

```text
cli-agent> /profile clear
cli-agent> /profile set style concise
cli-agent> /profile set format Jetpack Compose, @Composable функции
cli-agent> /profile set about Android dev, стек Jetpack Compose (single Activity, Material3, state hoisting)
cli-agent> /profile add constraint no XML layouts
cli-agent> /profile add constraint no ViewBinding and no findViewById
cli-agent> /profile add constraint stateless composables + state hoisting
cli-agent> /profile add constraint Kotlin only
cli-agent> /profile show
```

Тот же зонд `напиши экран списка элементов с кликом по элементу`.

Ожидания:
- Код — `@Composable fun ItemList(...)`, `LazyColumn { items(list) { ... } }`.
- Клик через `onClick` лямбду, проброшенную наружу (state hoisting).
- State через `remember`/`mutableStateOf` или проброс сверху, **не** мутируется внутри composable.
- **Нет** XML, нет `ViewBinding`, нет `RecyclerView`, нет `findViewById`.
- Material3-компоненты где уместно.

### A3-Android. Контрастная проверка (ядро задания)

| Ось | A1 View-based | A2 Compose |
|---|---|---|
| Разметка | XML-файл | `@Composable` |
| Доступ к view | ViewBinding | не нужен |
| Список | `RecyclerView.Adapter` | `LazyColumn` + `items` |
| Клики | `setOnClickListener` | `onClick` лямбда + hoisting |
| Запреты | no Compose | no XML / no ViewBinding |
| State | LiveData/ViewModel (опционально) | `remember` / hoisting |

> **Негативный сигнал:** если в A1 появился `@Composable` или `LazyColumn`, а в A2 —
> `RecyclerView`/XML → профиль не подмешивается или constraints игнорируются. Самый
> чувствительный тест Day 12.

### Вариант A4-Android. Смена «на лету» без рестарта сессии

Проверка, что профиль меняет поведение в том же чате:
```text
cli-agent> /profile clear
cli-agent> /profile set about Android dev, чистый Compose, без XML
cli-agent> /profile add constraint no XML layouts
cli-agent> напиши экран списка элементов с кликом по элементу
```
Ожидание: ответ на Compose. Затем:
```text
cli-agent> /profile clear
cli-agent> /profile set about Android dev, View-based, XML + ViewBinding
cli-agent> /profile add constraint no Jetpack Compose
cli-agent> напиши экран списка элементов с кликом по элементу
```
Ожидание: ответ на XML/ViewBinding. Доказывает, что профиль читается из live long-term memory
перед каждым запросом, а не кэшируется при старте агента.

## Сценарий C-Android. `/profile extract` — авто-извлечение (Android-диалог)

### C1-Android. Диалог с явными предпочтениями View-based

```text
cli-agent> /profile clear
cli-agent> я Android-разработчик, пишу на старом стеке — Activities, XML-разметки, ViewBinding
cli-agent> не люблю Compose, в проекте его нет, не предлагай
cli-agent> предпочитаю краткие ответы без воды, только код
cli-agent> доступ к view всегда через ViewBinding, findViewById не использую
```

### C2–C3-Android. Извлечение и проверка

```text
cli-agent> /profile extract
cli-agent> /profile show
```

LLM вернёт (формат `parseProfile`):
```text
style: кратко, только код
format:
about: Android-разработчик, стек View-based (Activities, XML, ViewBinding)
constraints:
- no Jetpack Compose
- доступ к view через ViewBinding
```

Ожидаемые распарсенные поля:
- `style` ≠ null (краткий/только код).
- `about` ≠ null (Android, View-based, Activities/XML/ViewBinding).
- `constraints` содержит запрет на Compose + требование ViewBinding.

### Вариант C4-Compose. Тот же тест, но диалог про Compose

```text
cli-agent> /profile clear
cli-agent> я пишу на Jetpack Compose, single Activity, Material3
cli-agent> не использую XML-разметки и ViewBinding вообще
cli-agent> люблю stateless composables с state hoisting
cli-agent> /profile extract
cli-agent> /profile show
```

Ожидание: `about` → Compose/Material3/single Activity; `constraints` → no XML, no ViewBinding.
Подтверждает, что экстрактор различает парадигмы, а не отдаёт шаблонный профиль.

> C4-Compose vs C1-Android — второй контрастный тест, уже на уровне авто-извлечения: один и тот
> же `ProfileExtractor` должен по разному диалогу вытащить разные `about`/`constraints`.

## Сценарий D-Android. `--auto-profile` — авто-извлечение каждые 5 ходов (Compose-диалог)

```bash
cli-agent chat --auto-profile
```

```text
cli-agent> /profile clear
cli-agent> я Android-разработчик, пишу на Compose, single Activity
cli-agent> не использую XML и ViewBinding, только @Composable
cli-agent> люблю краткие ответы
cli-agent> объясни чем remember отличается от rememberSaveable
# ↑ ~4-й ход диалога (turnCount растёт от запросов к LLM)
cli-agent> покажи LazyColumn с кликом по элементу
# ↑ ~5-й ход — должен сработать авто-extract
cli-agent> /profile show
```

Ожидание: после ~5-го хода `/profile show` показывает inferred профиль (Compose / no XML /
краткость) без ручного `/profile extract`. Если профиль пуст — авто-извлечение не сработало
(проверить `autoProfileEvery=5`, `turnCount % 5 == 0`).

> `turnCount` инкрементится в `chat()` после ответа ассистента. Считай только запросы к модели,
> а не `/profile`-команды (они через `handleProfile`, а не `chat()`).

## Сценарий E-Android. Персистентность + кросс-чат (Android-профиль переживает рестарт)

```text
# сессия 1
cli-agent> /profile set about Android dev, View-based, XML + ViewBinding
cli-agent> /profile add constraint no Jetpack Compose
cli-agent> /profile add constraint Kotlin only
cli-agent> /exit
```

```bash
cat $XDG_DATA_HOME/cli-agent/long-term.json
```

Ожидаемое содержимое:
```json
{"profile":{"about":"Android dev, View-based, XML + ViewBinding","constraints":["no Jetpack Compose","Kotlin only"]}}
```

```text
# сессия 2 — НОВЫЙ чат
cli-agent> /profile show          → профиль на месте
cli-agent> напиши экран списка элементов с кликом по элементу
```

Ожидание: ответ на View-based (XML/ViewBinding/RecyclerView), **без** Compose — профиль из
прошлой сессии применился в новом чате автоматически. Это и есть «global long-term».

Контр-проверка (Compose-профиль в новом чате): повторить E-Android, но поставить Compose-профиль
→ в новом чате после рестарта ответ должен быть на Compose. Доказывает, что персистится именно
актуальный профиль, а не захардкоженный.

---

## Чек-лист приёмки (соответствие заданию)

### Общие
- [x] Профиль создан — `UserProfile(style, format, about, constraints)` (MemoryLayer.kt).
- [x] Описаны предпочтения — style/format/about/constraints.
- [x] Подключён к каждому запросу — Сценарий B + `ContextAwareAgentProfileTest`.
- [x] Разные профили → разные ответы — Сценарий A (контрастная таблица A1/A2).
- [x] Учитывает автоматически — Сценарии C (`/profile extract`) и D (`--auto-profile`).
- [x] Персистентность — Сценарий E (рестарт + новый чат).
- [x] Эволюция схемы — `UserProfileSchemaTest` (старый JSON без `about`).
- [x] Инвариант совместимости — Сценарий D1 (без `--auto-profile` поведение = Day 11, нет доп. вызовов).

### Android-специфика
- [x] Разные профили → разные ответы: A1-Android (View-based) vs A2-Android (Compose) — контрастная таблица, 6 осей.
- [x] Смена на лету: A4-Android — тот же чат, смена парадигмы без рестарта.
- [x] Авто-извлечение различает парадигмы: C1-Android (View-based) vs C4-Compose.
- [x] Кросс-чат персистентность: E-Android — View-based и Compose профиль оба переживают рестарт и применяются в новом чате.
- [x] Негативные сигналы зафиксированы: Compose в A1-Android / XML+RecyclerView в A2-Android = профиль не работает.

## Зависимости
Задача 07 (верификация). Этот файл расширяет manual-REPL часть `07-verification.md`
конкретными пошаговыми данными и Android-адаптацией.
