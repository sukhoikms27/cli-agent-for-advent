# Задача 05. CLI-команда /profile + флаг --auto-profile

## Цель
Явное наполнение профиля + opt-in авто-извлечение.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt`

## Что изменить

### 1. Флаг
```kotlin
private val autoProfile by option("--auto-profile", help = "Auto-extract user profile every N turns").flag()
```
При `autoProfile`: `ProfileExtractor(client, model)` → агенту с `autoProfileEvery = 5`.

### 2. Диспетч `when` (рядом с `/memory`)
```kotlin
input.startsWith("/profile") -> handleProfile(input, agent)
```

### 3. `handleProfile(input, agent)` — через `agent.getProfile()/setProfile()`
```
/profile | /profile show           — показать профиль
/profile set style <text>          — set style
/profile set format <text>         — set format
/profile set about <text>          — set about
/profile add constraint <text>     — append constraint
/profile remove constraint <text>  — remove (точное совпадение, fallback contains)
/profile extract                   — on-demand LLM-извлечение из диалога + merge
/profile clear                     — очистить весь профиль
```
- `set <field>`: прочитать текущий профиль (`UserProfile()` если null), `copy(field = text)`, `setProfile`.
- `add constraint`: `copy(constraints = (cur.constraints) + text)`.
- `remove constraint`: `copy(constraints = cur.constraints.filterNot { it == text || it.contains(text) })`.
- `extract`: `val ex = ProfileExtractor(client, model); val cur = agent.getProfile(); val inferred = ex.extract(agent.getHistory(), cur); agent.setProfile(ex.mergeProfile(cur, inferred))`. (Нужен `client`/`model` в handleProfile — передать как в handleStrategy.)
- `clear`: `agent.setProfile(null)`.

### 4. `printHelp()` — секция `/profile`

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `/profile set style concise` → `/profile show` показывает стиль.
- `/profile add constraint "no RxJava"` → `/profile remove constraint "no RxJava"` → пусто.
- `/profile clear` → профиль null (`/profile show` = empty).

## Зависимости
Задача 04 (аксессоры).
