# Задача 04. Интеграция профиля в ContextAwareAgent

## Цель
Агент умеет читать/писать профиль и (opt-in) автоматически извлекать его каждые N ходов.

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt`

## Что изменить

### 1. Конструктор — новые параметры (default = выкл)
```kotlin
private val profileExtractor: ProfileExtractor? = null,
private val autoProfileEvery: Int = 0   // 0 = авто-извлечение выключено
```

### 2. Поле
```kotlin
private var turnCount = 0
```

### 3. Аксессоры профиля
```kotlin
suspend fun getProfile(): UserProfile? = getLongTermMemory().profile

suspend fun setProfile(profile: UserProfile?) {
    setLongTermMemory(getLongTermMemory().copy(profile = profile))
}
```
(`getLongTermMemory`/`setLongTermMemory` уже есть с Day 11.)

### 4. В `chat()` — после `contextManager?.onAssistantResponse(...)` (перед return)
```kotlin
turnCount++
if (profileExtractor != null && autoProfileEvery > 0 && turnCount % autoProfileEvery == 0) {
    val current = getProfile()
    val inferred = profileExtractor.extract(history, current)
    setProfile(profileExtractor.mergeProfile(current, inferred))
}
```

## Инвариант совместимости
Без `profileExtractor` (по умолчанию) — поведение идентично Day 11, доп. LLM-вызовов нет.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- Существующий путь `chat()` без профиля/extractor работает как прежде.
- С `profileExtractor` + `autoProfileEvery=2`: после 2 ходов extractor вызван, профиль персистится.

## Зависимости
Задача 03.
