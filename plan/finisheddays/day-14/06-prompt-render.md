# Задача 06. Рендер инвариантов в system prompt (`PromptBuilder`)

## Что
Инварианты явно учитываются в рассуждениях (требование day14) — рендерятся блоком
`[Project invariants]` в system prompt. Это **первый слой defense-in-depth** (правило в промпте);
второй слой — программная проверка (`InvariantGuard`, задача 08).

## Зависимости
05 (`LongTermMemory.invariants`).

## Реализация
Правка `src/main/kotlin/com/cliagent/agent/PromptBuilder.kt`:

`PromptBuilder` уже принимает `LongTermMemory?` и рендерит его блоки (`[Long-term memory]`,
`[Working memory]` через `renderBlock()`). Добавить рендер инвариантов:

```kotlin
class PromptBuilder(
    private val baseSystem: ChatMessage,
    private val longTerm: LongTermMemory?,
    private val working: WorkingMemory?
) {
    fun build(): ChatMessage {
        // base + [long-term] + [working] + [project invariants]
        // ...
    }
}
```

Рендер (новый private метод или расширение `LongTermMemory`):
```kotlin
private fun renderInvariants(invariants: List<Invariant>): String? =
    invariants.takeIf { it.isNotEmpty() }?.joinToString(
        separator = "\n",
        prefix = "[Project invariants — you MUST NOT propose solutions that violate these]\n",
        postfix = ""
    ) { iv -> "- [${iv.id}] ${iv.rule}  (${iv.category.name.lowercase()})" }
```

Блок добавляется в `build()` если `renderInvariants != null`. Акцент в заголовке «MUST NOT
violate» — даёт модели явный сигнал соблюдения.

## Проверка (задача 11, расширение `PromptBuilderTest`)
- `LongTermMemory(invariants=[...])` → `build().content` содержит `[Project invariants]` + текст
  правила + `MUST NOT`.
- `LongTermMemory()` (пустой) → блок отсутствует (ноль регресса: при отсутствии инвариантов
  поведение = день 13).
- `longTerm == null` → блок отсутствует.
- Порядок: `[Long-term]` → `[Working]` → `[Project invariants]` (инварианты последними —
  «свежее в памяти»/recency для модели).

## Решения
- **Через `PromptBuilder`, не через `systemPromptAppendix`** — инварианты — данные памяти
  (живут в LongTermMemory), а не статичный текст. Единый путь рендера слоёв памяти.
- **Текст на английском** — системные промпты проекта на английском (`StagePromptTemplates`,
  `SystemPrompts`), консистентно.
- **`category` в выводе** — даёт модели контекст (запрет vs. стек), улучшает соблюдение.
- Дублирование с `UserProfile.constraints` — **намеренное**: профиль (soft, стиль) и инварианты
  (hard, правила) решают разные задачи и могут частично пересекаться; это нормально и ожидаемо.
