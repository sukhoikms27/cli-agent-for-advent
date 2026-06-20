# Задача 01. Модель инварианта (`Invariant`) + категория

## Что
Доменная модель инварианта — жёсткого правила проекта, которое агент не имеет права нарушать.
Отдельная от профиля сущность (см. решение #4 в README).

## Зависимости
— (первая задача, фундамент домена).

## Реализация
Новый файл `src/main/kotlin/com/cliagent/state/invariant/Invariant.kt`:

```kotlin
package com.cliagent.state.invariant

import kotlinx.serialization.Serializable

/**
 * Категория инварианта — группировка для отображения и приоритизации.
 */
@Serializable
enum class InvariantCategory {
    STACK,      // технологический стек (Kotlin, Ktor, ViewBinding)
    BAN,        // запрещённые технологии (no Compose, no RxJava, no XML)
    ARCH,       // архитектурные решения (MVI, MVVM, Repository)
    BUSINESS    // бизнес-правила (free-API-only, бюджет, лимиты)
}

/**
 * Инвариант — жёсткое правило проекта, которое ассистент не имеет права нарушать (день 14,
 * третий столп недели 3). Хранится отдельно от диалога в [com.cliagent.memory.LongTermMemory.invariants].
 *
 * В отличие от [com.cliagent.memory.UserProfile.constraints] (персонализация, soft, только в
 * промпте), инвариант — hard-правило: проверяется программно через [InvariantChecker].
 *
 * @param id       стабильный идентификатор (для /invariants remove и ссылок в Violated)
 * @param rule     текст правила на естественном языке (кормит judge-промпт и system prompt)
 * @param category группа для отображения/фильтрации
 */
@Serializable
data class Invariant(
    val id: String,
    val rule: String,
    val category: InvariantCategory = InvariantCategory.BUSINESS
)
```

## Проверка
- `@Serializable` + round-trip через `AppJson` (см. `UserProfileSchemaTest` — образец).
- Legacy JSON (без этого поля — невозможно, новый тип) → N/A.
- Покрытие в `InvariantTest` (задача 11).

## Почему `category`
day14 перечисляет 4 класса инвариантов (архитектура/решения/стек/бизнес). Категория даёт:
- читаемое `/invariants show` (группировка);
- extension point — можно по-разному проверять категории (позже).
