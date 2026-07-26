# Day 35 — Handoff для продолжения на другом устройстве

> Этот файл — точка входа для нового агента. Прочитать **первым делом** перед любыми действиями.
> Дата составления: 2026-07-19. Ветка: `task/day-35`.

---

## 1. Кто пользователь и какая у него задача

**Пользователь** — Leo (l.suhinin), ревьюер студенческих заданий в Яндексе, направление Android
(Sprint 7, «Notes App» — консольное приложение «Заметки и Архивы» на Kotlin/JVM).

**Боль:** ручной флоу ревью занимает ~20-30 минут на студента:
1. Ловит задание в Яндекс.Мессенджере (формат «Новое задание: [N] ФИО (email)» + ссылка на Tracker-тикет)
2. Клеймит в CRM
3. Открывает PR студента
4. Читает код, пишет line-by-line комментарии
5. Создаёт review в GitHub от своего юзера
6. Submit (Approve / Request changes)

**Цель:** автоматизировать максимум. В идеале — watches задания в мессенджере → автоматически
клеймит → прогоняет AI-ревью → создаёт PENDING review (чтобы пользователь сам доработал и submit'нул).

**Это часть капстоуна Day 35 (AI Advent Challenge #8).**

---

## 2. Что уже реализовано (demo-MVP + Watcher)

### 2.1 Модуль `:review-bot` (ВЫПОЛНЕН)

**Расположение:** `review-bot/` в корне проекта. Зарегистрирован в `settings.gradle.kts`.

**Запуск (единственный способ):**
```bash
./gradlew :review-bot:run --args="<команда> <аргументы>"
```

> ⚠️ ВАЖНО: `cli-agent review-bot ...` **НЕ работает** — пользователь явно отказался регистрировать
> subкоманду в корневом CLI (отказался от переноса классов в корень). Это **решение пользователя**,
> не баг. Не «чинить».

**Команды:**
- `review --pr <url> --sprint N --student "ФИО" [--publish] [--no-rag]` — прогон ревью
- `index-kb` — индексация встроенного чеклиста (один раз)
- `watch` — Ktor-сервер на `127.0.0.1:8082`, endpoint `/messenger-event` (принимает POST от userscript)
- `install-userscript` — печатает путь к userscript'у для Tampermonkey

**Структура модуля** (`review-bot/src/main/kotlin/com/cliagent/review/`):
- `ReviewBotApp.kt`, `ReviewBotFactory.kt`, `ReviewBotPaths.kt`
- `ReviewPipeline.kt` — orchestration
- `gh/` — `GhCli.kt` (deadlock-safe ProcessBuilder), `PrUrlParser.kt`, `PrFiles.kt` (kotlinx.serialization),
  `DiffLineIndexer.kt`, `PendingReviewPoster.kt` (`event: String? = null` = PENDING)
- `review/` — `ReviewResult.kt`, `ReviewPrompt.kt` (с humanization + Java-поблажки + JSON schema),
  `ChecklistParser.kt`, `ResponseParser.kt`
- `watch/` — `WorkEvent.kt`, `MessageParser.kt`, `ClaimStateStore.kt` (atomic write),
  `WorkFilter.kt`, `Notifier.kt`
- `cli/` — `MainCli.kt` (subcommands), `ReviewCommand.kt`, `WatchCommand.kt`, `IndexKbCommand.kt`,
  `InstallUserscriptCommand.kt`

**Ресурсы:**
- `review-bot/src/main/resources/checklist-sprint-7.md` — встроенный чеклист (11 пунктов)
- `review-bot/src/main/resources/yandex-messenger-watcher.user.js` — Tampermonkey userscript

**Тесты:** 47 unit-тестов (`review-bot/src/test/...`).

### 2.2 Demo-репозиторий `review-bot-demo` (ВЫПОЛНЕН)

Приватный репо под `sukhoikms27` на GitHub.

- **main** — starter (Main.kt + build.gradle.kts Kotlin/JVM)
- **PR #1** `feature/clean-vizhian` — клон решения vizhian (9 файлов). Ожидаемо → ACCEPT.
  URL: `https://github.com/sukhoikms27/review-bot-demo/pull/1`
- **PR #2** `feature/buggy-solution` — тот же код + 6 намеренных багов. Ожидаемо → REJECT.
  URL: `https://github.com/sukhoikms27/review-bot-demo/pull/2`

**Оба прогона прошли успешно** на момент handoff:
- PR #1: ACCEPT, все 11 пунктов ✅, 9 soft-рекомендаций, pending review создан
- PR #2: REJECT, 6 ❌, 9 line-comments, pending review создан

### 2.3 Watcher (ВЫПОЛНЕН, но хрупкий)

- Tampermonkey userscript с MutationObserver на `.yamb-message-content`
- Извлекает trackerUrl из `<a class="link_md" href*="st.yandex-team.ru">`
- POST через `GM_xmlhttpRequest` в webhook на `localhost:8082`
- Smoke-test на синтетическом сообщении прошёл

**Известная проблема:** Яндекс.Мессенджер использует **бинарный WebSocket**, поэтому
`fetch`/`WS` monkey-patch невозможен. MutationObserver — единственный рабочий путь в userscript'е,
и он хрупкий (ломается при изменении DOM/классов).

---

## 3. КРИТИЧНО: что было ОТМЕНЕНО пользователем

Эти вещи **нельзя делать обратно** без явного разрешения пользователя:

1. **Перенос классов из `:review-bot` в корень `src/main/kotlin/com/cliagent/review/`** —
   пользователь сказал «мне не нравится подход с переносом». Rollback выполнен, классы остались
   в модуле `:review-bot`. **Не предлагать снова.**

2. **Регистрация subкоманды в корневом `Main.kt`** (чтобы `cli-agent review-bot ...` работало) —
   то же решение пользователя: «Не чиню». Не трогать.

3. **Phase 1 roadmap-задачи** (ClaimProvider, Collector, build-стадия в pipeline, `--auto-claim`) —
   перечислены в плане, но **пользователь их не запрашивал активно**. Не начинать без явного запроса.

---

## 4. Текущий разворот разговора (на момент handoff)

Пользователь спросил: «Если я дам доступ через MCP браузера для отладки скриптов или это может
быть совершенно другой вариант без скриптов?»

Я (текущий агент) ответил, что **CDP через MCP (`mcp__claude-in-chrome__*`) — лучшая альтернатива**
userscript'у:
- Перехватывает бинарные WS-фреймы через `Network.webSocketFrameReceived` (решает главную боль)
- Не требует `GM_xmlhttpRequest`/CORS-обхода
- Позволяет итеративную отладку без перезалива userscript'а

**Минус CDP:** Chrome должен быть запущен с `--remote-debugging-port=9222`. Варианты:
- Отдельный профиль: `open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/chrome-debug`
- Или перезапуск обычного Chrome с флагом

**Предложил:** провести разведку через CDP — посмотреть DOM Яндекс.Мессенджера, перехватить WS,
понять формат (JSON? protobuf?), и потом **финально выбрать**: userscript vs CDP-watcher vs прямой
WS-клиент.

**Жду ответа пользователя** на вопрос: «Хочешь запустить Chrome с remote debugging — и провести
разведку? Или сначала обсудим архитектурно?»

### Альтернативы, которые я перечислил (для контекста нового агента):

| Вариант | Сложность | Надёжность | ToS-риск | Без браузера |
|---|---|---|---|---|
| Tampermonkey (текущий) | средняя | средняя | низкий | ❌ |
| **CDP через MCP** | низкая | высокая | низкий | ❌ |
| Прямой WS API | очень высокая | высокая | высокий | ✅ |
| Playwright | высокая | средняя | низкий | ❌ (хэдлесс) |

---

## 5. БЕЗОПАСНОСТЬ — критично

### 5.1 Ключ z.ai

Пользователь передал в открытой переписке: `CLI_AGENT_API_KEY=763926ed34344cd2952d6b3667aae0be.BF4Odq1HJMLcZTEy`.

**ПРАВИЛА (не нарушать):**
1. **НЕ писать ключ в любые файлы** в `/Users/l.suhinin/Documents/cli-agent/` — это git-репозиторий,
   любой файл с ключом = утечка в историю коммитов.
2. Только через **env-переменную** при запуске:
   ```bash
   CLI_AGENT_API_KEY=763926... ./gradlew :review-bot:run --args="..."
   ```
3. Если на новом устройстве у пользователя **нет этого ключа** — попросить его вставить ключ
   через env. Не угадывать, не подставлять из истории.

> На новом устройстве пользователь должен либо взять тот же ключ из своего аккаунта z.ai,
> либо сгенерировать новый. **Не хранить ключ в файле.**

### 5.2 Этика перехвата мессенджера

- Поддерживаем только **«умное соответствие»** (отфильтрованные работы из нужных спринтов), НЕ слепой
  граббер всех заданий.
- Пользователь **сам обязан** убедиться, что автоматизация клейма разрешена политикой Яндекса.
  Агент не проверяет юридическую сторону, но напоминает.
- `WorkFilter` (существующий) реализует: дедупликация + `consecutiveWorks < 2` + только mySprints.

### 5.3 Demo-репо под живым аккаунтом

`sukhoikms27/review-bot-demo` — **приватный** под реальным аккаунтом пользователя. Любые
тесты review pipeline создают реальные pending review в этом репо. Это ок для демо (пользователь
в курсе), но **не запускать с `--publish` без явного подтверждения** — `--publish` сабмитит review
сразу (не pending).

---

## 6. Где что лежит

| Что | Где |
|---|---|
| План Day 35 v2 (основной) | `plan/newdays/day35-plan-v2.md` |
| План Day 35 v1 (Codebase Explorer — альтернатива) | `plan/newdays/day35-plan.md` |
| **Этот handoff** | `plan/newdays/day35-handoff.md` |
| Модуль review-bot | `review-bot/` |
| Demo-репо (GitHub) | `github.com/sukhoikms27/review-bot-demo` (private) |
| Чеклист Sprint 7 | `review-bot/src/main/resources/checklist-sprint-7.md` |
| Tampermonkey userscript | `review-bot/src/main/resources/yandex-messenger-watcher.user.js` |
| Корневой CLI (не трогать!) | `src/main/kotlin/com/cliagent/Main.kt` |
| Архитектура проекта | `AGENTS.md` (корень) + `plan/finisheddays/day-16/global-plan.md` |

---

## 7. Что НЕ делать новому агенту

1. ❌ Не переносить классы из `:review-bot` в корень — пользователь отменил
2. ❌ Не регистрировать subкоманду в `Main.kt` — пользователь отменил
3. ❌ Не писать z.ai ключ в файлы — только env
4. ❌ Не запускать `review --publish` без явного подтверждения
5. ❌ Не начинать Phase 1 roadmap (ClaimProvider/Collector) без явного запроса
6. ❌ Не предлагать альтернативный стек (Kotlin/Spring/Ktor — зафиксированы в AGENTS.md)
7. ❌ Не использовать `git` напрямую — только через пользователя (AGENTS.md: «totally blocks git usage»)

---

## 8. Что обсуждалось и какие решения приняты

- **RAG над кодом** — отвергли (нет профита, требует клонирования). RAG только над **текстом**
  (встроенный чеклист).
- **AST-гибрид для Codebase Explorer** — согласован как альтернативная концепция в `day35-plan.md`,
  не реализован.
- **Гуманизация комментариев** — обязательна. Промпт в `ReviewPrompt.kt`: mentor tone, объяснение
  ПОЧЕМУ, ссылки на kotlinlang.org/android docs, без LLM-tells, на русском.
- **«Поблажки к Java-стилю»** — студенты только переехали с Java на Kotlin. Промпт содержит
  whitelist того, что НЕ флаговать (Java-идиомы, приемлемые в переходный период).
- **Комментарии от юзера, не от бота** — реализовано через `gh api` (использует `gh auth status`
  текущего юзера). На demo-машине это `sukhoikms27`.
- **Pending review (не сабмит сразу)** — реализовано через `event: null` в ReviewBodyDto
  (строка `"PENDING"` даёт 422 от GitHub; PENDING создаётся OMITTED event field).

---

## 9. План дальнейших шагов (после ответа пользователя)

1. **Разведка через CDP** (если пользователь согласен):
   - Подключиться к Chrome через `mcp__claude-in-chrome__*`
   - Посмотреть DOM Яндекс.Мессенджера
   - Перехватить WS-фреймы, декодировать
   - Зафиксировать формат в `review-bot/docs/parser-spec.md`
2. **Реализовать `CdpWatcher`** как альтернативу userscript'у (если разведка успешна)
3. **Или** оставить userscript как есть — пользователь решает

Дальнейшие Phase 1 задачи (ClaimProvider, Collector, build-стадия) — только по явному запросу.

---

## 10. Профиль для нового агента (по AGENTS.md)

Это **Бизнес-фича** профиль (новый модуль + интеграция), НЕ поиск бага.
Стадии: Research → Plan → Executing → Validation → Report.

> Stack фиксируется AGENTS.md: **Kotlin/JVM 21, Spring Boot (backend), Compose Multiplatform
> (mobile/desktop), Kotlin/JS+Vue (web)**. Для review-bot — Kotlin/JVM + clikt + mordant + Ktor.
