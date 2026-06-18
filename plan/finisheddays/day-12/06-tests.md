# Задача 06. Тесты

## Цель
Покрыть поле `about`, экстрактор, инъекцию профиля в агента и эволюцию схемы.

## Тест-классы (`src/test/kotlin/com/cliagent/...`)

**`agent/PromptBuilderTest.kt`** (расширить существующий):
- профиль с `about` рендерится (`About: ...`).
- `mergeProfile`-кейс не тут (см. ProfileExtractorTest).

**`agent/ProfileExtractorTest.kt`** (MockK):
- mock `LlmClient` возвращает структурированный текст → `extract()` парсит style/format/about/constraints.
- `extract` на `LlmResult.Error` → возвращает `current` без изменений.
- `mergeProfile`: existing поля сохраняются при пустых inferred; constraints аккумулируются + distinct.

**`agent/ContextAwareAgentProfileTest.kt`** (MockK):
- (a) непустой профиль в `memoryStoreMock` → `messages[0].content` содержит `User profile` + значения.
- (b) `autoProfileEvery=2`, mock extractor (или spy) → после 2 `chat()` профиль обновлён/персистён.

**`memory/UserProfileSchemaTest.kt`**:
- round-trip `UserProfile(about="x")`.
- старый JSON без `about` → `about == null`.

## Критерии готовности
- `./gradlew test` — все зелёные (включая Day 11).

## Зависимости
Задачи 01–05.
