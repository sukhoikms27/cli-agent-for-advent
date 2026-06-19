# Задача 07. Аксессоры инвариантов в `ContextAwareAgent`

## Что
Агент получает методы `getInvariants()/setInvariants()` — точная копия паттерна
`getProfile()/setProfile()` (день 12). Делегирование через LongTermMemory.

## Зависимости
05 (`LongTermMemory.invariants`).

## Реализация
Правка `src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt` — добавить в секцию
memory/profile accessors:

```kotlin
// — day 14: project invariants accessors (для /invariants команд) —

suspend fun getInvariants(): List<Invariant> =
    getLongTermMemory().invariants

suspend fun setInvariants(invariants: List<Invariant>) {
    setLongTermMemory(getLongTermMemory().copy(invariants = invariants))
}

/** Добавить инвариант (если id уже есть — обновить rule/category, не дублировать). */
suspend fun addInvariant(invariant: Invariant) {
    val current = getInvariants()
    val updated = (current.filterNot { it.id == invariant.id } + invariant)
        .sortedBy { it.category.name }   // стабильный порядок для отображения
    setInvariants(updated)
}

/** Удалить инвариант по id; true если был удалён. */
suspend fun removeInvariant(id: String): Boolean {
    val current = getInvariants()
    if (current.none { it.id == id }) return false
    setInvariants(current.filterNot { it.id == id })
    return true
}
```

Использует существующие `getLongTermMemory()/setLongTermMemory()` (день 11) — персистентность
«бесплатно» через `MemoryStore.saveLongTermMemory`.

## Проверка (задача 11, расширение `ContextAwareAgentTaskStateTest` или новый `ContextAwareAgentInvariantsTest`)
- `setInvariants([a])` → `getInvariants()` == `[a]` (round-trip через MemoryStore mock).
- `addInvariant(b)` при `[a]` → `[a, b]` (порядок по category).
- `addInvariant(a')` (тот же id) → обновляет, не дублирует.
- `removeInvariant("a")` → true, список стал `[]`; `removeInvariant("zzz")` → false.
- Не затирает profile/knowledge/decisions (`setInvariants` использует `copy`, не новый объект).
- Паттерн идентичен `getProfile/setProfile` (зеркало day-12 тестов).

## Решения
- **`addInvariant`/`removeInvariant`-хелперы** — `/invariants`-команды (задача 09) оперируют
  единичными инвариантами; хелперы инкапсулируют read-modify-write (аккуратная персистентность
  через `copy`, как `setProfile`).
- **`sortedBy(category)`** — стабильный порядок в `/invariants show` (STACK → BAN → ARCH →
  BUSINESS, алфавит enum).
- **Без отдельного поля в ContextAwareAgent** — state lives в LongTermMemory (single source of
  truth); агент — тонкий делегат (как для профиля).
