# Задача 06. Тесты

## Цель
Покрыть модель слоёв, стораж, PromptBuilder, эволюцию схемы и инъекцию в агента. Завести test-инфру (её в репо нет).

## Файлы

### `build.gradle.kts` (правка)
Добавить test-зависимости и JUnit Platform:
```kotlin
testImplementation(platform("org.junit:junit-bom:5.12.2"))
testImplementation("org.junit.jupiter:junit-jupiter")
testImplementation("io.mockk:mockk:1.13.16")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
```
```kotlin
tasks.withType<Test> { useJUnitPlatform() }
```

### Тест-классы (`src/test/kotlin/com/cliagent/...`)

**`memory/JsonChatStoreWorkingMemoryTest.kt`** (`@TempDir` для `chatsDir`, `runTest`):
- save/load round-trip `WorkingMemory`.
- clear обнуляет.
- working-память чата A не видна в чате B.

**`memory/JsonLongTermStoreTest.kt`** (или в составе `JsonChatStore`):
- save/load global `LongTermMemory`.
- переживает новый инстанс стора (кросс-сессия): `JsonChatStore(dir1)` save → новый `JsonChatStore(dir1)` load видит данные.
- `clearLongTermMemory()` очищает.

**`agent/PromptBuilderTest.kt`**:
- (a) пустые слои → `build().content == base.content`.
- (b) только working → содержит `[Working memory`, нет `[Long-term`.
- (c) оба → порядок base → long-term → working.
- (d) `UserProfile` рендерится (готовность Day 12).

**`memory/ChatDataSchemaEvolutionTest.kt`**:
- декодировать pre-Day-11 JSON (без `workingMemory`) в `ChatData` → `workingMemory == null`, остальные поля целы. Ключевой guard конвенции эволюции схемы.

**`agent/ContextAwareAgentMemoryInjectionTest.kt`** (MockK):
- mock `MemoryStore` возвращает непустые working+long-term; mock `LlmClient` перехватывает `ChatRequest`.
- assert `messages[0].content` содержит оба блока.
- с пустой памятью → первый system-message контент == base prompt.

## Конвенции (CLAUDE.md)
- JUnit 5 + MockK для unit, `runTest` для корутин.
- `@TempDir` + `@BeforeEach`/`@AfterEach` для персистентности.

## Критерии готовности
- `./gradlew test` — все тесты зелёные.

## Зависимости
Задачи 01–05.
