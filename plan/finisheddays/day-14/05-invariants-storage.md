# Задача 05. Слот хранения: `LongTermMemory.invariants`

## Что
Инварианты хранятся **отдельно от диалога** (требование day14) в global `LongTermMemory`.
Эволюция схемы через дефолт — старые long-term JSON'ы грузятся без миграций.

## Зависимости
01 (`Invariant`).

## Реализация
Правка `src/main/kotlin/com/cliagent/memory/MemoryLayer.kt` — добавить поле в `LongTermMemory`:

```kotlin
@Serializable
data class LongTermMemory(
    val knowledge: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val profile: UserProfile? = null,
    val invariants: List<Invariant> = emptyList()   // день 14: жёсткие правила проекта
) {
    fun isEmpty(): Boolean =
        knowledge.isEmpty() && decisions.isEmpty() &&
            (profile == null || profile.isEmpty()) &&
            invariants.isEmpty()   // ← обновить
}
```

`MemoryLayer.kt` уже импортирует `com.cliagent.state.TaskState`; добавить
`import com.cliagent.state.invariant.Invariant` (зависимость `memory → state.invariant`
однонаправленная, допустима — как `memory → state` уже есть).

## Проверка (задача 11, расширение `ChatDataSchemaEvolutionTest`)
- Legacy long-term JSON без `invariants` → грузится, `invariants == emptyList()` (дефолт).
- Round-trip: `LongTermMemory(invariants = listOf(Invariant("no-compose", "...", BAN)))` →
  encode → decode → поле сохранено.
- `isEmpty()` = true при пустом профиле/knowledge/decisions/invariants; false — когда есть
  хотя бы один инвариант (блок рендерится только при непустых данных).

## Решения
- **`List<Invariant>`, не `Map`** — инварианты упорядочены (важно для отображения и стабильности
  retry-feedback), и `id` уже есть в `Invariant`.
- **Global (в LongTermMemory), не per-chat** — инварианты — правила *проекта*, общие для всех
  чатов/сессий (как профиль). переживают restart. Per-chat — extension point (README).
- **Эволюция схемы** — `ignoreUnknownKeys=true` + дефолт `emptyList()` → старые чаты не ломаются
  (конвенция CLAUDE.md: add-with-defaults-never-remove).
