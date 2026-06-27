# Задача 07. Тестовая инфраструктура модуля `:mcp-server`

## Цель

В модуле `:mcp-server` сейчас **нет тестов** (и нет test-deps в `build.gradle.kts`). Добавить
зависимости и JUnit-platform, чтобы задачи 08–11 могли писать юнит-тесты. Инфраструктура — без
самих тестов (0 тестов после этой задачи OK).

## Зависимости

Самостоятельна (можно ставить в любой момент батча 2). Образец test-deps — корневой
`build.gradle.kts` (JUnit 5 BOM + mockk + coroutines-test). Дополнительно для Ktor-мокинга:
`ktor-client-mock` той же версии.

## Файл (правка)

`mcp-server/build.gradle.kts`

## Что добавить

```kotlin
dependencies {
    // ... существующие implementation/runtimeOnly ...

    // Testing (Day 18) — выровнено с корневым проектом (день 11 gate) + ktor-client-mock.
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
    // Ktor MockEngine — для WeatherClientTest (задача 09): мок geocoding/forecast без сети.
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

## Ключевые инварианты

- **Версии выровнены с корнем** (`ktorVersion`/`kotlinxCoroutinesVersion` уже объявлены в модуле,
  Day 16 gate) — `ktor-client-mock:$ktorVersion` той же версии, что клиентские артефакты.
- **`useJUnitPlatform()`** — без неё JUnit 5 тесты не запустятся (Gradle по умолчанию ждёт JUnit 4).
  Тот же блок, что в корневом `build.gradle.kts`.
- **mockk 1.13.16** — та же версия, что в корне (день 11), для единообразия API mock'ов.
- **E2E-флагов не добавляем** — модуль mcp-server сам по себе тестируется юнит-тестами 08–11; E2E
  (raw JSON-RPC / процесс) остаётся в корневом тесте Day 17 (`McpClientServerIntegrationTest`,
  gated флагом) — Day 18 его не трогает.

## Решения

- **Тесты в модуле `:mcp-server`**, не в корне — тестируется internal-код погодного домена
  (`WeatherStore`, `WeatherClient`, `WeatherScheduler`, `aggregate`), который живёт в этом модуле.
  Корневой тестовый classpath его не видит (другой gradle-проект).
- **JUnit 5 + mockk + coroutines-test + ktor-mock** — минимальный набор, покрывающий все 4 тест-класса
  дня: storage (tmp-dir, без mock'ов), client (MockEngine), scheduler (runTest + fakes), summary
  (чистая функция, без mock'ов).

## Критерии готовности

- `./gradlew :mcp-server:test` запускается без ошибок (0 тестов — OK, инфраструктура готова).
- `./gradlew :mcp-server:build` — green (test-deps резолвятся, компиляция test-src OK).

## Зависимости (задачи)

Фундамент для 08–11. Не зависит от main-кода (можно ставить первым в батче 2).
