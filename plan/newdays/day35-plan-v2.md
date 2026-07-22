# День 35 (v2) — Review Bot: автоматизация ревью студенческих заданий

> **Статус (обновлено 2026-07-22): ЧАСТИЧНО РЕАЛИЗОВАНО.**
> **Demo-MVP + Watcher готовы и работают end-to-end** (см. §15):
>  - **Review pipeline** — модуль `:review-bot`, `review --pr <url> --sprint 7`, RAG над embedded
>    чеклистом, LLM review с humanization + Java-поблажками, pending review через `gh` от юзера.
>    Демо: PR #1 (ACCEPT), PR #2 (REJECT) — оба успешны.
>  - **Watcher** — Tampermonkey userscript (MutationObserver на `.yamb-message-content`) →
>    webhook receiver (`watch`) → Notifier с terminal preview + Enter/Esc. Smoke-test на
>    синтетическом сообщении из реального образца пользователя успешен.
>
> Полный Phase 1 (ClaimProvider + Collector + build-стадия + auto-claim) — следующий этап.
>
> План лежит рядом с `day35-plan.md` (Codebase Explorer) — это две **независимые** концепции;
> текущая выбрана как основной капстоун дня 35 (реальная задача с живой болью), Codebase Explorer
> оставлен как альтернативный вариант.
>
> **Дата плана:** 2026-07-19. **Ревизия:** 2026-07-20 — источник заданий перепроектирован:
> Tracker poll **отпадает** (нет признака курса), Messenger bot webhook **отпадает** (бота нельзя
> добавить в чат). Primary = **Tampermonkey userscript** (monkey-patch fetch/WS/XHR + MutationObserver
> fallback), fallback = **Chrome DevTools Protocol**.
> **Автор:** cli-agent swarm (неделя 7).
> **Контекст:** пользователь — ревьюер заданий студентов в Яндексе (Android-направление).

---

## 0. Постановка задачи (боль)

Ревьюер получает поток студенческих работ по Android-направлению. Полный флоу одной работы
содержит рутину, которая отъедает время и где есть **живая боль — конкуренция за перехват**:
другие ревьюеры используют скрипты/автокликеры, чтобы забирать задания быстрее.

### Описание флоу (как сейчас, руками)

1. Задание приходит в чат **Яндекс Мессенджера**: сообщение содержит спринт, студента, номер
   задачи в Яндекс-Трекере (и markdown-ссылку на неё).
2. В Трекере — тот же набор: спринт, студент, ссылка на работу в CRM.
3. В CRM (по ссылке из Трекера):
   - системное описание задания,
   - вкладка отчёта по текущей проверке,
   - история работы (итерации при повторных пересдачах),
   - если работа свободна — кнопка «взять работу» / «перепроверить работу».
4. При получении работы:
   1. закрепить за собой (кнопка «взять» / API / curl),
   2. проставить реакцию в чате (что взял),
   3. во вкладке отчёта — получить ссылку на PR в GitHub,
   4. проверить, что `gh` доступен, PR доступен, изменения достаточны,
   5. сходить в Wiki/Confluence по спринту, взять чеклист проверки,
   6. склонировать репозиторий, собрать его,
   7. пройтись по чеклисту, проверить поведение,
   8. при критических замечаниях (крэш / не собирается / чеклист) — вернуть на переделку,
   9. провести code review (упор на знания текущего и прошлых спринтов),
   10. вернуть артефакт: accept/reject + line-by-line PR comments (а не один общий коммент).

### Две главные зоны автоматизации

| Зона | Цель |
|------|------|
| **Перехват** (A) | паритет с ботами коллег: быстро клеймить **свою** работу под фильтр, без arms race |
| **Полное ревью** (B) | автоматизировать сборку/clone/context и подготовить AI code review + line comments |

---

## 1. Этический и политический framing перехвата

> **Я разделяю перехват на два режима и поддерживаю только один из них.**

### ❌ Слепой граббер (НЕ поддерживаю)

Полный auto-claim всей появляющейся работы без фильтра. Минусы:
- arms race с коллегами — каждый добавляет своего бота,
- можно забрать работу, которую не сможешь качественно проверить (не твой спринт),
- почти наверняка нарушение внутренней политики Яндекса; при разборе выглядит плохо,
- антипаттерн: «моя автоматизация лучше чужой», а не «моя работа сделана хорошо».

### ✅ Smart parity (поддерживаю, дефолт плана)

Монитор + API-клейм **только под фильтр**:
- только мои спринты (в данном случае — любой, см. ответ пользователя),
- hard constraint: `consecutiveWorks < 2` (правило пользователя — не более 2 работ подряд),
- API-first: Tracker REST API + reverse-engineered CRM backend (HTTP, без UI-автоматизации),
- default — notify + one-key claim (пользователь жмёт Enter, бот делает остальное),
- полный auto-claim — opt-in флагом, но только по фильтру.

> **Пользователь обязан сам убедиться**, что автоматизация клейма разрешена внутренней политикой.
> Если нет — smart parity остаётся ок (фильтр = моя работа), слепой граббер — нет. Этот план не
> снимает ответственности с пользователя за соблюдение политик работодателя.

---

## 2. Доступность интеграций — что есть, чего нет

| Интеграция | Статус | Источник в архитектуре |
|------------|--------|------------------------|
| **Confluence API** | ✅ есть | чеклисты по спринту |
| **Yandex Tracker API** | ✅ есть (IAM) | статусы, исполнитель, апдейт issue. **Polling как источник заданий — НЕ подходит** (см. ниже) |
| **GitHub (`gh` CLI)** | ✅ есть | PR checkout, review, line comments |
| **Yandex Messenger webhook (server)** | ⚠️ ограничен | ловит сообщения **боту**, а бота нельзя добавить в целевой чат → **отпадает** как primary |
| **Messenger в браузерной вкладке** | ✅ всегда | чат открыт в браузере; перехват через userscript или CDP — это и есть primary |
| **CRM backend** | ❓ reverse-engineer | F12 → Network → кнопка «взять работу» → POST/PUT запрос → HTTP-клейм |

### Почему Tracker poll НЕ подходит как источник заданий

У Tracker API **нет признака курса** (courses — множество). Poll по `assignee=null AND status=open`
вернёт **все** свободные задания по всем курсам и спринтам — отфильтровать до «моего курса» на
уровне API нельзя. Это значит:
- poll будет ловить чужие задания,
- фильтрация возможна только post-factum (по описанию issue), что медленно и хрупко,
- как источник первичного обнаружения Tracker **непригоден**.

Tracker остаётся в архитектуре только для: assignee update, status transition, fetch issue
metadata — но **не для обнаружения** новых работ.

### Fallback-стратегия источника заданий (browser-first)

`WatcherStrategy` — интерфейс, имплементации переключаются конфигом. Источник — браузерная вкладка
с Яндекс.Мессенджером (открыта у пользователя постоянно):

| Имплементация | Как | Latency | Зависимости |
|---------------|-----|---------|-------------|
| **UserscriptWatcher** (primary) | Tampermonkey + monkey-patch `fetch`/`WebSocket`/`XHR` + `MutationObserver` на DOM как fallback внутри скрипта; POST на локальный webhook Review Bot'а | < 100ms | Tampermonkey, доступ к странице Messenger |
| **CdpWatcher** (fallback) | Chrome с `--remote-debugging-port=9222`, Review Bot подключается по Chrome DevTools Protocol, ловит `Network.webSocketFrameReceived` / `Network.responseReceived` + `Network.getResponseBody` | < 100ms | отдельный Chrome-профиль с debug-портом |
| ~~MessengerWebhookWatcher~~ | ~~webhook от Yandex Messenger bot API~~ | — | **отпадает** (бота нельзя в чат) |
| ~~TrackerPollWatcher~~ | ~~poll Tracker API~~ | — | **отпадает** (нет признака курса) |

> **Рекомендация:** `UserscriptWatcher` как primary, `CdpWatcher` как fallback. Обе реализуют
> `WatcherStrategy`, переключаются флагом `watcher.strategy={userscript|cdp}`. Альтернативно —
> запускать обе параллельно с дедупликацией по `messageId` (userscript на основной вкладке, CDP на
> backup-профиле).

### Сравнение discarded-вариантов (для истории)

| Вариант | Почему нет |
|---------|------------|
| Bot webhook (server-side) | бота нельзя добавить в целевой чат |
| Tracker API poll | нет признака курса, ловит чужое |
| OS Notification bridge (AppleScript) | lossy (~150 символов), троттлинг, не хватает CRM-ссылки |
| mitmproxy (TLS MITM) | инвазивно, требует CA-сертификат, риск на corp-сети |
| Messenger desktop scraping | хрупко, нарушает TOS мессенджера |

### Почему userscript быстрее автокликеров коллег

Автокликер/расширение коллег работает по циклу: **render → detect → click**. Userscript
перехватывает на уровне **API-ответа до рендера**: `fetch.then()` срабатывает раньше, чем React
отрендерит сообщение в DOM, и намного раньше, чем любой click-based automation сможет среагировать.
Это даёт **детерминированную победу по latency** (~100ms vs ~500ms+ у clicker'ов), не требуя
агрессивного граббинга — бот просто быстрее замечает **свою** работу.

---

## 3. High-level архитектура

```
┌──────────────────────────────────────────────────────────────────┐
│                   Review Bot (:review-bot module)                 │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────────┐
│  Источник: браузерная вкладка Yandex Messenger                    │
│  ┌────────────────────────────────────┐  ┌──────────────────────┐│
│  │ Tampermonkey userscript            │  │ Chrome --remote-     ││
│  │ (monkey-patch fetch/WS/XHR +       │  │ debugging-port=9222  ││
│  │  MutationObserver fallback)        │  │ (CDP watcher)        ││
│  └──────────────┬─────────────────────┘  └──────────┬───────────┘│
│                 │ POST /messenger-event             │ CDP attach ││
└─────────────────┼──────────────────────────────────┼────────────┘
                  │                                  │
                  ▼                                  ▼
┌──────────────────────────────────────────────────────────────────┐
│  Watcher  (WatcherStrategy — receiver/CDP client)                 │
│    • UserscriptWatcher (push, < 100ms)           ── primary       │
│    • CdpWatcher (CDP Network events, < 100ms)    ── fallback      │
│    filter: sprint IN mySprints AND course=ANDROID                 │
│    constraint: consecutiveWorks < 2  ← правило пользователя       │
└────────────────────┬─────────────────────────────────────────────┘
                     │ (найдена подходящая работа + constraint ок)
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  Notifier (terminal / desktop)                                    │
│    показывает preview: спринт, студент, PR (если уже виден)       │
│    ждёт ввода: Enter = забрать, Esc = пропустить                  │
│    (opt-in --auto-claim: пропускает этот шаг по фильтру)          │
└────────────────────┬─────────────────────────────────────────────┘
                     │ (claim подтверждён)
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  Claimer  (ClaimProvider — интерфейс)                             │
│    • CRM HTTP (reverse-engineered из кнопки «взять работу»)       │
│    • Tracker API: PATCH assignee=me, transition status=in_review  │
│    • Messenger react на исходное сообщение (если бот в чате)      │
└────────────────────┬─────────────────────────────────────────────┘
                     ▼
┌──────────────────────────────────────────────────────────────────┐
│  Collector (parallel via coroutines)                              │
│    Tracker: description, sprint, student, history (past iters)    │
│    CRM: PR link, current iteration                                │
│    Confluence: checklist for sprint                               │
│    GitHub (gh): PR metadata, diff, files, commits                 │
│  → ContextBundle                                                  │
└────────────────────┬─────────────────────────────────────────────┘
                     ▼
       ┌──────────────────────┬──────────────────────────┐
       ▼                      ▼                          ▼
┌──────────────┐   ┌────────────────────┐   ┌──────────────────────┐
│  Verifier    │   │  KB Indexer        │   │  KB Indexer          │
│              │   │  (offline, build)  │   │  (offline, build)    │
│ gh auth ok?  │   │ Confluence +       │   │ разделён по разделам:│
│ PR checkout  │   │ course materials   │   │  base/view/compose   │
│ diff size    │   │ → RAG index        │   │ → embeddings         │
│ assembleDebug│   │ indexed by sprint  │   │ per sprint + section │
└──────┬───────┘   └─────────┬──────────┘   └──────────┬───────────┘
       │                      │                          │
       └──────────────────────┴──────────────────────────┘
                              ▼
       ┌────────────────────────────────────────────────────┐
       │  Reviewer (LLM + RAG + checklist)                  │
       │    determine section: base / view / compose        │
       │    semantic search: «материалы спринта X, раздел Y»│
       │    → line-by-line review + verdict proposal        │
       │    → critical remarks (crash/build/checklist)      │
       └────────────────────┬───────────────────────────────┘
                            ▼
       ┌─────────────────────────────────────────────────────────┐
       │  Reporter  (твой апрув на вердикт)                      │
       │    • gh pr review (approve / request changes)           │
       │    • gh api: POST line comments на конкретные строки    │
       │    • CRM: verdict + критические замечания               │
       │    • Tracker: status transition                         │
       │    • Messenger: react (если бот в чате)                 │
       └─────────────────────────────────────────────────────────┘
```

---

## 4. Стек (в рамках инвариантов проекта)

| Компонент | Технология | Обоснование |
|-----------|------------|-------------|
| Язык | Kotlin (JVM 21) | инвариант проекта |
| Build | Gradle + Kotlin DSL, новый модуль `:review-bot` | зеркало `:support-app`, `:mcp-server` |
| CLI | clikt 4.4.0 | как все команды cli-agent |
| HTTP client | Ktor Client 3.4.3 | для Tracker/CRM/Confluence + локальный bridge userscript'а |
| Webhook receiver | Ktor Server 3.4.3 (minimal) | приём POST от Tampermonkey userscript (`/messenger-event`) |
| CDP client | `chrome-devtools-java-client` | подключение к Chrome через `--remote-debugging-port` (CdpWatcher) |
| Userscript | Tampermonkey (JS, не Kotlin) | инжектится в страницу Messenger, перехватывает fetch/WS |
| Serialization | kotlinx.serialization | JSON-договор с API |
| Coroutines | kotlinx-coroutines | parallel collection, async claims |
| LLM | multi-provider (z.ai / Ollama / openai-compatible) | `LlmClientFactory` из корня |
| RAG | `rag/` (RagIndexer, RagRetriever, StructuralChunker) | над курсом — embeddings текста работают |
| Embeddings | OllamaEmbeddingClient (nomic-embed-text) | локально, без API-key |
| GitHub | `gh` CLI через ProcessBuilder | PR review, line comments, checkout |
| Memory | `JsonLongTermStore` (reuse) | типовые ошибки, прошлые комментарии |
| Terminal output | mordant | preview, прогресс, таблицы |

**Явно отсутствует (по решению):** `agent/swarm/` (MVP — один pipeline), `agent/stage/` FSM
(review — линейный pipeline, не FSM), `state/` invariants.

---

## 5. Модуль `:review-bot` — структура

```
review-bot/
├── build.gradle.kts
└── src/main/kotlin/com/cliagent/reviewbot/
    ├── ReviewBotCommand.kt          # clikt entry: review-bot --watch [--auto-claim]
    ├── ReviewBotEngine.kt           # оркестратор: watch → notify → claim → collect → verify → review → report
    │
    ├── watch/                       # источник заданий (browser-first)
    │   ├── WatcherStrategy.kt        # interface: suspend fun start(onWork: (Work) -> Unit)
    │   ├── UserscriptWatcher.kt      # Ktor Server receiver: слушает POST /messenger-event от Tampermonkey
    │   ├── CdpWatcher.kt             # Chrome DevTools Protocol клиент (Network events)
    │   ├── MessageParser.kt          # парсинг тела Messenger-ответа → WorkEvent (regex по спринт/трекер/студент)
    │   ├── WorkFilter.kt             # course=ANDROID AND sprint IN mySprints AND consecutiveWorks < 2
    │   └── WorkEvent.kt              # data class: messageId, course, sprint, student, trackerId, crmUrl, rawBody
    │
    ├── userscript/                 # Tampermonkey-скрипт (JS, не Kotlin — вынести в src/main/resources)
    │   └── yandex-messenger-watcher.user.js
    │       # @match https://*.yandex-team.ru/messenger/*
    │       # monkey-patch: window.fetch, XMLHttpRequest, WebSocket
    │       # fallback: MutationObserver на контейнере чата
    │       # POST на http://localhost:8080/messenger-event
    │
    ├── claim/
    │   ├── ClaimProvider.kt          # interface: suspend fun claim(work): ClaimResult
    │   ├── CrmHttpClaimProvider.kt   # reverse-engineered HTTP (config-driven)
    │   ├── TrackerClaimProvider.kt   # PATCH issue assignee + transition
    │   └── MessengerReactor.kt       # bot API react на сообщение (если бот в чате)
    │
    ├── collect/
    │   ├── Collector.kt              # parallel: Tracker + CRM + Confluence + GitHub → ContextBundle
    │   ├── TrackerClient.kt          # REST API (IAM auth)
    │   ├── CrmClient.kt              # backend HTTP (PR link, iteration)
    │   ├── ConfluenceClient.kt       # checklist by sprint
    │   ├── GitHubClient.kt           # обёртка над gh CLI (view, diff, checkout, review)
    │   └── ContextBundle.kt          # data class: вся собранная информация о работе
    │
    ├── verify/
    │   ├── Verifier.kt               # gh auth, PR checkout, diff size, assembleDebug
    │   ├── GhChecker.kt              # `gh auth status`, `gh pr view`
    │   ├── PrChecker.kt              # diff size, files, commits
    │   └── BuildRunner.kt            # `git clone`, `./gradlew assembleDebug` через ProcessBuilder
    │
    ├── kb/                          # knowledge base (RAG над курсом)
    │   ├── KbIndexer.kt              # строит embeddings по Confluence + материалам
    │   ├── KbSection.kt              # enum: BASE_JAVA_KOTLIN, ANDROID_VIEW, ANDROID_COMPOSE
    │   ├── KbRetriever.kt            # semantic search по спринту + разделу
    │   └── TypicalMistakesStore.kt   # JsonLongTermStore: накопление типовых ошибок
    │
    ├── review/
    │   ├── Reviewer.kt               # LLM + RAG → line-by-line review + verdict proposal
    │   ├── SectionDetector.kt        # определяет раздел по содержимому PR (Compose vs View vs base)
    │   ├── ChecklistChecker.kt       # сверка с чеклистом Confluence
    │   ├── ReviewComment.kt          # data class: file, line, side, body
    │   └── VerdictProposal.kt        # ACCEPT / REJECT + critical remarks
    │
    └── report/
        ├── Reporter.kt               # финальный пуш после апрува пользователя
        ├── PrReviewer.kt             # `gh api .../reviews event=PENDING` — создаёт pending review от юзера
        ├── LineCommenter.kt          # `gh api` POST review comments на line+side
        ├── CrmReporter.kt            # verdict + критические замечания в CRM
        └── TrackerReporter.kt        # status transition в Tracker
```

### `build.gradle.kts` (скетч)

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}
dependencies {
    implementation(project(":"))   // LlmClient, LlmClientFactory, rag/, JsonLongTermStore
    implementation("io.ktor:ktor-client-core:3.4.3")
    implementation("io.ktor:ktor-client-cio:3.4.3")
    implementation("io.ktor:ktor-server-core:3.4.3")     // webhook receiver
    implementation("io.ktor:ktor-server-cio:3.4.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")
    implementation("com.github.kklisura.cdt:cdt-java-client:...")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.github.ajalt.mordant:mordant:2.5.0")
}
```

---

## 6. Перехват заданий — детальный дизайн

### 6.1 WatcherStrategy

```kotlin
interface WatcherStrategy {
    /**
     * Подписывается на появление новых работ. Вызывает [onWork] для каждой подходящей.
     * Фильтрация и constraint (consecutiveWorks < 2) — внутри Watcher'а.
     */
    fun start(scope: CoroutineScope, onWork: suspend (WorkEvent) -> Unit)
}
```

### 6.2 UserscriptWatcher (primary) — Tampermonkey + monkey-patch

**Идея:** userscript инжектится в страницу Яндекс.Мессенджера и перехватывает API-ответы **до
рендера**. POST'ит на локальный endpoint Review Bot'а.

**Слои перехвата внутри userscript** (от раннего к позднему):

1. **`window.fetch` monkey-patch** — обёртка, которая клонирует `Response` и читает тело. Срабатывает
   **до** того, как React/SPA отрендерит сообщение.
2. **`XMLHttpRequest.prototype.open/send`** — на случай, если Messenger ходит через XHR.
3. **`WebSocket.prototype`** — обёртка над конструктором; слушает `message` события. Актуально, если
   Messenger использует WS для real-time (типично для мессенджеров).
4. **`MutationObserver` на контейнере чата** — fallback: если Yandex сменит API-контракт или
   переключится на binary protobuf, скрипт продолжит ловить отрендеренный DOM.

**Скетч userscript** (`review-bot/src/main/resources/userscript/yandex-messenger-watcher.user.js`):

```javascript
// ==UserScript==
// @name         Yandex Messenger Review Watcher
// @match        https://*.yandex-team.ru/messenger/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @connect      localhost
// ==/UserScript==

(function() {
    'use strict';

    const WEBHOOK = 'http://localhost:8080/messenger-event';
    const ASSIGNMENT_RE = /(спринт|sprint)[^\d]*(\d+)/i;

    function forward(payload) {
        GM_xmlhttpRequest({
            method: 'POST',
            url: WEBHOOK,
            headers: { 'Content-Type': 'application/json' },
            data: JSON.stringify({ ts: Date.now(), ...payload }),
        });
    }

    // --- 1. fetch ---
    const origFetch = window.fetch;
    window.fetch = async function(...args) {
        const resp = await origFetch.apply(this, args);
        try {
            const url = args[0]?.url || args[0];
            if (/messenger.*\/(messages|events|updates)/i.test(String(url))) {
                const clone = resp.clone();
                const body = await clone.text();
                if (ASSIGNMENT_RE.test(body)) {
                    forward({ source: 'fetch', url: String(url), body });
                }
            }
        } catch (_) {}
        return resp;
    };

    // --- 2. XMLHttpRequest ---
    const origOpen = XMLHttpRequest.prototype.open;
    const origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
        this.__rbUrl = url; this.__rbMethod = method;
        return origOpen.call(this, method, url, ...rest);
    };
    XMLHttpRequest.prototype.send = function(body) {
        this.addEventListener('load', () => {
            try {
                if (/messenger/i.test(String(this.__rbUrl)) &&
                    ASSIGNMENT_RE.test(String(this.responseText))) {
                    forward({ source: 'xhr', url: String(this.__rbUrl), body: this.responseText });
                }
            } catch (_) {}
        });
        return origSend.call(this, body);
    };

    // --- 3. WebSocket ---
    const OrigWS = window.WebSocket;
    function PatchedWS(...args) {
        const ws = new OrigWS(...args);
        ws.addEventListener('message', (ev) => {
            try {
                const data = typeof ev.data === 'string' ? ev.data : '';
                if (data && ASSIGNMENT_RE.test(data)) {
                    forward({ source: 'websocket', body: data });
                }
            } catch (_) {}
        });
        return ws;
    }
    PatchedWS.prototype = OrigWS.prototype;
    window.WebSocket = PatchedWS;

    // --- 4. DOM fallback (MutationObserver) ---
    document.addEventListener('DOMContentLoaded', () => {
        const observer = new MutationObserver((mutations) => {
            for (const m of mutations) {
                for (const node of m.addedNodes) {
                    if (node.nodeType !== 1) continue;
                    const text = node.textContent || '';
                    if (ASSIGNMENT_RE.test(text)) {
                        forward({ source: 'dom', text, html: node.innerHTML?.slice(0, 4096) });
                    }
                }
            }
        });
        const tryAttach = () => {
            const chat = document.querySelector('[data-role="messages-container"]')
                      || document.querySelector('.messages-list');
            if (chat) observer.observe(chat, { childList: true, subtree: true });
            else setTimeout(tryAttach, 1000);   // SPA может рендериться позже
        };
        tryAttach();
    });
})();
```

**Receiver в Review Bot** (`UserscriptWatcher.kt`) — minimal Ktor Server:

```kotlin
class UserscriptWatcher(
    private val parser: MessageParser,
    private val filter: WorkFilter,
) : WatcherStrategy {
    override fun start(scope: CoroutineScope, onWork: suspend (WorkEvent) -> Unit) {
        embeddedServer(CIO, port = 8080) {
            routing {
                post("/messenger-event") {
                    val raw = call.receiveText()
                    val event = parser.parse(raw) ?: return@post
                    if (filter.matches(event)) scope.launch { onWork(event) }
                    call.respondText("ok")
                }
            }
        }.start(wait = false)
    }
}
```

**Latency-бюджет:**

```
chat server → browser (network)     ~50-200ms (вне нашего контроля)
fetch.then → userscript forward     ~1-5ms
HTTP POST localhost                 ~1-2ms
WorkFilter + notify                 ~5ms
─────────────────────────────────────────
total                               < 250ms (типично < 100ms)
```

Для сравнения: автокликер коллег → render (~150ms) → detect (~50ms) → click (~100ms) → сервер
обрабатывает click → ~400-800ms. **Userscript детерминированно быстрее на ~300-700ms.**

**Дедупликация** — userscript может выстрелить несколько раз (fetch + WS + DOM по одному событию).
`MessageParser` извлекает `messageId` (или hash содержимого) и `WorkEvent` дедупится в
`WorkFilter` (in-memory set за последние 60s + persisted handled-ids).

### 6.3 CdpWatcher (fallback) — Chrome DevTools Protocol

Если Tampermonkey недоступен (CSP блокирует, политика компании, нет прав), Review Bot подключается
к Chrome напрямую через CDP.

**Запуск Chrome:**

```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
    --remote-debugging-port=9222 \
    --user-data-dir=~/chrome-review-profile
```

**CdpWatcher** через `cdt-java-client`:

```kotlin
class CdpWatcher(
    private val parser: MessageParser,
    private val filter: WorkFilter,
) : WatcherStrategy {
    override fun start(scope: CoroutineScope, onWork: suspend (WorkEvent) -> Unit) {
        val client = ChromeDevToolsClient.builder()
            .url("ws://localhost:9222/...")  // авто-обнаружение tab по URL
            .build()

        // 1. Network domain: ловим HTTP-ответы
        client.network().enable()
        client.network().responseReceivedListeners += { event ->
            if (event.mimeType.contains("json") &&
                event.url.contains("messenger")) {
                val body = client.network().getResponseBody(event.requestId).await()
                val parsed = parser.parse(body)
                if (parsed != null && filter.matches(parsed)) {
                    scope.launch { onWork(parsed) }
                }
            }
        }

        // 2. WebSocket frames (если Messenger использует WS)
        client.network().webSocketFrameReceivedListeners += { event ->
            val parsed = parser.parse(event.response.payloadData)
            if (parsed != null && filter.matches(parsed)) {
                scope.launch { onWork(parsed) }
            }
        }
    }
}
```

**Плюсы/минусы vs userscript:**
- ✅ ноль модификаций страницы (робастнее к CSP/политике),
- ✅ ловит network-level события напрямую,
- ❌ требует запуск отдельного Chrome-профиля с флагом,
- ❌ если закроешь Chrome — бот слепнет (userscript живёт в обычной вкладке).

**Когда переключаться:** если Tampermonkey не исполняется на странице Messenger (CSP блокирует
inline-инъекции) — переключаемся `watcher.strategy=cdp`.

### 6.4 MessageParser — извлечение WorkEvent из сырого тела

API-ответ Яндекс.Мессенджера — JSON, конкретная схема которой мы не знаем заранее. Парсер работает
в два слоя:

1. **Regex-слой** (быстрый фильтр): ищем паттерны «спринт N», «sprint N», трекер-id (например
   `ANDROID-1234`), markdown-ссылки `github.com/.../pull/N` и tracker-links.
2. **LLM-слой** (опционально, для сложных): если regex нашёл «что-то похожее», но не вытащил
   структуру — кормим сырой фрагмент LLM с промптом «извлеки {sprint, student, trackerId, crmUrl}».

```kotlin
data class WorkEvent(
    val messageId: String,         // для дедупликации (или hash)
    val course: String,            // "ANDROID" (для фильтра курса)
    val sprint: Int?,
    val student: String?,
    val trackerId: String?,
    val crmUrl: String?,
    val prLink: String?,
    val rawBody: String,           // для дебага и fallback
)

class MessageParser(/* regex config */) {
    fun parse(rawBody: String): WorkEvent? { /* regex + LLM fallback */ }
}
```

> **Unknown:** точная схема ответа Messenger API. На стадии Research (см. §15 Phase 1) — открыть F12,
> поймать реальный response, зафиксировать поля в `parser-spec.md`. Паттерны вынести в конфиг.

### 6.5 WorkFilter — правило `consecutiveWorks < 2` + дедупликация

```kotlin
class WorkFilter(
    private val myCourse: String,             // "ANDROID"
    private val mySprints: Set<Int>,          // пусто = все (по ответу пользователя)
    private val state: ClaimStateStore,
    private val dedupe: DedupeCache,          // messageId за последние 60s
) {
    suspend fun matches(event: WorkEvent): Boolean {
        if (!dedupe.firstTime(event.messageId)) return false          // дедупликация
        if (event.course != myCourse) return false
        if (mySprints.isNotEmpty() && event.sprint != null && event.sprint !in mySprints) return false
        return state.consecutiveWorks() < 2                            // правило пользователя
    }
}
```

`ClaimStateStore` хранит в JSON (XDG):
- `consecutiveWorks` — сколько работ взято подряд без перерыва,
- `lastClaimAt` — timestamp последнего клейма,
- правило сброса: после завершения проверки (verdict sent) ИЛИ после N минут бездействия → сброс.

### 6.6 Claim — одно нажатие vs auto

По умолчанию:
```
[19:42:03] NEW WORK  sprint=4  student=Ivanov  tracker=ANDROID-1234
            → preview: PR github.com/.../pull/56 (если виден в CRM)
            → Press ENTER to claim, ESC to skip
```

`--auto-claim` — пропускает Notifier, сразу вызывает Claimer. **Только при включённом фильтре**
(не слепой граббер).

### 6.7 ClaimProvider — абстрактный, конфиг-привязанный

```kotlin
interface ClaimProvider {
    suspend fun claim(work: WorkEvent): ClaimResult
}

class CrmHttpClaimProvider(
    private val httpClient: HttpClient,
    private val endpoint: String,        // из конфига (reverse-engineered)
    private val authProvider: () -> String,
) : ClaimProvider {
    override suspend fun claim(work: WorkEvent): ClaimResult {
        // POST на endpoint с идентификатором работы
    }
}
```

CRM-эндпоинт определяется реверс-инжинирингом: F12 → Network → кнопка «взять работу» → копируем
URL/method/headers/body → кладём в конфиг `review-bot.conf`:

```hocon
crm {
    claimEndpoint = "https://crm.internal.yandex-team.ru/api/assignments/claim"
    method = "POST"
    authHeader = "OAuth ..."   # из env, не в репозиторий
}
```

Дополнительно:
- **TrackerClaimProvider** — `PATCH /v2/issues/<id>` (`assignee=me`) + transition в `in_review`.
  (Tracker здесь — только для апдейта статуса, не для обнаружения.)
- **MessengerReactor** — Bot API: поставить реакцию на исходное сообщение. Работает только если у
  бота есть права на react в чате — проверяется отдельно (не блокирует claim).

---

## 7. Полное ревью — детальный дизайн

### 7.1 Collector (параллельный сбор)

```kotlin
class Collector(
    private val tracker: TrackerClient,
    private val crm: CrmClient,
    private val confluence: ConfluenceClient,
    private val github: GitHubClient,
) {
    suspend fun collect(work: WorkEvent): ContextBundle = coroutineScope {
        val trackerCtx = async { tracker.fetch(work.trackerId) }
        val crmCtx     = async { crm.fetch(work.crmUrl) }
        val checklist  = async { confluence.checklist(work.sprint) }
        val pr         = async { github.prOverview(crmCtx.await().prLink) }
        ContextBundle(
            tracker = trackerCtx.await(),
            crm = crmCtx.await(),
            checklist = checklist.await(),
            pr = pr.await(),
        )
    }
}
```

### 7.2 Verifier

Последовательно, fail-fast:
1. `gh auth status` — если не авторизован → stop, понятная ошибка.
2. `gh pr view <link>` — PR существует, не закрыт, author = студент.
3. `gh pr diff --stat` — diff size >= `minDiff` (иначе «недостаточно изменений» → reject early).
4. `git clone` PR branch → определить тип проекта → собрать:
   - **Kotlin/JVM** (Notes App и подобные): `./gradlew build` (compile + test).
   - **Android** (Compose/View спринты): `./gradlew assembleDebug` (требует Android SDK).
   - Если нужный SDK недоступен → skip сборки + warning, продолжить review без неё (best-effort).
5. Если сборка упала → critical remark → verdict = REJECT (без LLM-ревью).

Тип проекта определяется по `build.gradle.kts` (наличие `com.android.application` plugin → Android;
иначе JVM). Всё через ProcessBuilder, вывод логируется.

### 7.3 Knowledge Base — RAG над курсом (здесь он легитимен)

В отличие от Codebase Explorer (где RAG выкинут), здесь RAG **обоснован** — база знаний курса это
**текст**: материалы лекций, чеклисты, типовые ошибки. Embeddings текста работают нормально.

**Секционирование по разделам** (т.к. студенты пишут в трёх разных стеках):

```kotlin
enum class KbSection {
    BASE_JAVA_KOTLIN,    // чистый код, без Android
    ANDROID_VIEW,        // XML, ViewBinding, lifecycle
    ANDROID_COMPOSE,     // @Composable, remember, state hoisting
}
```

`KbIndexer` строит индекс:
- источник: Confluence (материалы по спринту) + локальные материалы курса (если есть),
- метаданные каждого чанка: `{ sprint, section, type: lecture|checklist|example }`,
- embeddings через OllamaEmbeddingClient (nomic-embed-text).

`KbRetriever.retrieve(query, sprint, section)` — semantic search с фильтром по метаданным.

`SectionDetector` — по содержимому PR определяет раздел:
- `@Composable`, `remember`, `mutableStateOf` → ANDROID_COMPOSE,
- `findViewById`, `ViewBinding`, XML-layouts → ANDROID_VIEW,
- нет Android-импортов → BASE_JAVA_KOTLIN.

### 7.4 Reviewer — AI sweet spot

```kotlin
class Reviewer(
    private val llm: LlmClient,
    private val model: String,
    private val kb: KbRetriever,
    private val checklistChecker: ChecklistChecker,
) {
    suspend fun review(ctx: ContextBundle, diff: String): ReviewResult {
        val section = SectionDetector.detect(diff)
        val kbContext = kb.retrieve(
            query = "материалы спринта ${ctx.work.sprint}, раздел $section",
            sprint = ctx.work.sprint,
            section = section,
        )
        val prompt = ReviewPrompt.build(diff, ctx.checklist, kbContext, section)
        val llmResult = llm.chat(prompt)
        return ReviewParser.parse(llmResult)   // → line-by-line comments + verdict
    }
}
```

**Вывод Reviewer'а:**

```kotlin
data class ReviewResult(
    val studentName: String,                   // для «Привет, [имя]!»
    val checklist: List<ChecklistItem>,        // 11 пунктов чеклиста с ✅/❌
    val criticalRemarks: List<String>,         // критические замечания (блок 2)
    val recommendations: List<String>,         // рекомендации, не влияющие на результат (блок 3)
    val lineComments: List<ReviewComment>,     // line-by-line комментарии в GitHub PR
    val verdict: VerdictProposal,              // ACCEPT / REJECT (выводится из checklist + critical)
)

data class ChecklistItem(
    val text: String,                          // точная формулировка из авторского чеклиста
    val passed: Boolean,                       // true = ✅, false = ❌
    val evidence: String?,                     // на чём основано решение (файл:строка или описание)
)

data class ReviewComment(
    val file: String,
    val line: Int,
    val side: CommentSide,                     // LEFT (base) / RIGHT (head)
    val body: String,                          // очеловеченный комментарий на русском
)

enum class VerdictProposal { ACCEPT, REJECT }
```

### CRM-репорт — точная структура авторского шаблона

LLM должна выдать verdict-репорт **ровно в формате**, который ревьюер использует в реальной работе.
Формат зафиксирован в `ReviewPrompt` как жёсткий шаблон:

```
Привет, [имя студента]!

1. Выполнение требований задания:

   ✅/❌ Есть меню с возможностью добавления и просмотра архивов
   ✅/❌ Есть меню с возможностью добавления и просмотра заметок
   ✅/❌ Есть возможность добавлять и просматривать текст заметок
   ✅/❌ Приложение не позволяет создать архив или заметку без имени (с пустым именем)
   ✅/❌ Приложение не позволяет создать заметку без содержания (с пустым текстом)
   ✅/❌ Нет повторений кода
   ✅/❌ Ошибочный ввод пользователя корректно обрабатывается
   ✅/❌ Из любого меню можно выйти и попасть на предыдущее меню или выйти из программы, если это просмотр архива
   ✅/❌ Приложение успешно компилируется и выполняется без ошибок
   ✅/❌ Весь код не написан в одном файле Main
   ✅/❌ Весь код приложения написан на Kotlin

2. ⚠️ Критические замечания:
   [конкретные проблемы со ссылками на файл:строку, или «Таких нет, ты молодец! Работа принята!»]

3. 🍏 Рекомендации, не влияющие на результат проверки работы:
   [что можно улучшить, не блокируя принятие; или «Хорошая работа, рекомендаций нет!»]
```

**Список чеклиста — фиксированный** (из авторского шаблона ревьюера). LLM не придумывает пункты —
она только проставляет ✅/❌ на основе анализа кода + RAG по чеклисту спринта.

**Verdict mapping:**
- Если все 11 пунктов ✅ И нет critical remarks → `verdict = ACCEPT`.
- Если есть хотя бы один ❌ по критическим пунктам (компилируется, нет повторов кода, обработка
  ввода, навигация) ИЛИ есть critical remarks → `verdict = REJECT` + «вернуть на переделку».
- Рекомендации (блок 3) **никогда** не влияют на verdict.

### Промпт-инструкция LLM — правила humanization

Комментарии читают студенты-новички. Это **не технический отчёт**, это **наставничество**.
`ReviewPrompt.build()` собирает системный промпт со следующими жёсткими правилами:

**Тон (mentor, не robot):**
- живой русский язык, как senior пишет junior'у в PR,
- дружелюбный, но по делу; без канцелярита и бюрократии,
- обращение на «ты», естественно,
- поощрять хорошее («молодец», «хороший вариант»), не только критиковать.

**Структура каждого line-comment:**
- **что не так** — конкретно, со ссылкой на строку/паттерн,
- **почему это важно** — к чему приведёт (краш, дублирование, баг при возврате меню и т.д.),
- **как починить** — конкретный код или паттерн, не размытое «consider improving»,
- **ссылка на документацию** — если тема освещена в официальной документации Kotlin
  (kotlinlang.org) или в материалах спринта, дать прямую ссылку.

**Запрещённые формулировки (LLM-tells):**
- «Важно отметить», «Следует учесть», «В данном случае», «Тем не менее», «Необходимо понимать»,
- «It is recommended to...», «Please note that...»,
- любые вводные конструкции-вата; переходить сразу к сути.

**Принцип «объяснять ПОЧЕМУ, не ЧТО»:**
- ❌ «нет проверки пустого имени»
- ✅ «Тут `name` не проверяется на пустоту — можно создать архив с пустым именем, а задание
  требует это запретить (пункт чеклиста). Добавь `if (name.isBlank()) { println("Имя не может быть
  пустым"); return }`. Подробнее про `isBlank`/`isEmpty`:
  https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/is-blank.html»

**Правило «поблажки к Java-стилю студентов» (важно для early-спринтов):**

Студенты только что перешли с Java на Kotlin. Многие идиомы им ещё неизвестны. Промпт обязан
содержать явный «whitelist» того, что **НЕ флаговать** как ошибку:

| Не-идиоматично, но ОК (не флаговать) | Идиоматичный путь (для soft-рекомендации) |
|--------------------------------------|-------------------------------------------|
| `if-else` цепочки | `when` |
| Явные null-checks (`if (x != null)`) | `?.let {}` / `?.run` / Элвис |
| `try-catch` для парсинга | `runCatching` / `toIntOrNull` |
| Обычный `class` с ручными getter/setter | `data class` |
| `Scanner(System.`in`)` | `readLine()` |
| `for (i in 0..size)` | `forEach` / `map` / `indices` |

**Флаговать только:**
- **нарушения 11 пунктов чеклиста** (жёсткие критерии — это блокирует приём),
- **баги** (краш при вводе, невозможность выйти из меню, утечка данных, и т.д.),
- **дублирование кода** (в чеклисте — отдельный пункт).

**Не-идиоматичный, но рабочий Kotlin-код** → в «рекомендации» (блок 3, не влияет на verdict), с
мягкой формулировкой: «можно переписать короче через `when`, если уже знаком с этой конструкцией».

> Это правило критично для early-спринтов (base Java/Kotlin, Notes App). Без него ревью
> превращается в «перепиши всё на идиоматичный Kotlin» — а это не цель спринта и несправедливо к
> студенту, который Kotlin ещё не освоил.

**Контекст:**
- упор на знания текущего и прошлых спринтов (через RAG по материалам курса),
- line-by-line comments в GitHub PR (отдельно от CRM-репорта),
- сравнение с **авторским чеклистом спринта** (фиксированные 11 пунктов),
- выделение критических замечаний отдельно (блок 2 в CRM-репорте),
- рекомендации — отдельно, не влияют на verdict (блок 3),
- не флаговать то, что выходит за рамки текущего спринта (студент ещё не дошёл до этих тем).

**Few-shot в системном промпте:**
3-5 пар «плохой»/«хороший» комментарий + пример полного CRM-репорта (по шаблону выше) для
калибровки стиля. Примеры калибруются под **конкретный спринт** (Notes App — паттерны ООП, дженерики,
обработка ввода; более поздние спринты — Compose, lifecycle и т.д.).

### 7.5 Reporter — Pending Review flow (HITL через GitHub UI)

**Ключевая идея:** бот не постит финальный review сам. Он создаёт **pending review** (статус
`PENDING`) — он виден только автору review (тебе), студент его **не видит**. Ты дорабатываешь
комментарии прямо в GitHub UI и жмёшь «Submit review».

#### Идентичность автора

Все GitHub-вызовы идут через `gh` CLI, аутентифицированный как **личный GitHub-аккаунт ревьюера**
(через `gh auth login`, который у тебя уже сделан). Никакого bot-identity, никаких GitHub Apps,
никаких отдельных токенов. Pending review и все комментарии в нём создаются **от твоего юзера**.

#### Flow

```
1. Reviewer выдаёт ReviewResult (verdict proposal + line comments + summary)
2. Terminal preview (опц., быстрая проверка, что не мусор)
3. Reporter создаёт PENDING review одной API-операцией:
     gh api repos/{owner}/{repo}/pulls/{pr}/reviews \
         -f event=PENDING \
         -f body="<summary>" \
         -F 'comments=[
               {"path":"...","line":N,"side":"RIGHT","body":"..."},
               ...
             ]'
   → GitHub возвращает review_id, review в статусе PENDING
4. Bot печатает: "Pending review #42 создан с 5 комментариями. Открой PR в GitHub, доработай и сабмитни."
5. Ты открываешь PR в браузере → видишь СВОЙ pending review со всеми комментариями
6. В GitHub UI: редактируешь / удаляешь / добавляешь комментарии (inline, мышкой)
7. Жмёшь «Submit review» → выбираешь: Approve / Request changes / Comment
   → review становится видимым студенту от твоего имени
8. После твоего сабмита (если надо): bot может дополнительно закрыть Tracker-issue / обновить CRM
   (эти шаги — отдельные опциональные команды, не автоматически)
```

#### Почему Pending, а не post-with-approval-in-terminal

| Аспект | Terminal-approval (отвергнуто) | Pending review (текущий) |
|--------|--------------------------------|---------------------------|
| Где валидируешь | терминал | привычный GitHub UI |
| Редактирование комментария | перезапуск pipeline или sed'ом | inline, мышкой |
| Автор review | кто угодно | **твой GitHub-юзер** (через `gh` auth) |
| Что видит студент до аппрува | рискованно | **ничего** (PENDING скрыт от не-автора) |
| Что видит зритель демо | «бот напечатал approval» | **ты реально ревьюишь** в GitHub — рабочий процесс |

> **HITL на вердикт — обязателен** и теперь реализован нативно через Pending review: студент не
> видит review, пока ты его не сабмитнёшь в GitHub UI. Это и есть подтверждение. После сабмита —
> дополнительные интеграции (CRM verdict, Tracker transition) выполняются **отдельными**
> командами/флагами, не автоматически.

#### CLI для Reporter

```bash
# Создать pending review (default): бот готовит, ты сабмитишь в GitHub UI
cli-agent review-bot post-review --pr <github-url> [--pending]

# Post directly (dangerous): сразу publish review от твоего имени, без pending-стадии
cli-agent review-bot post-review --pr <github-url> --publish --event REQUEST_CHANGES
# ↑ только с явным --publish, по умолчанию всегда --pending
```

---

## 8. CLI-команды

```bash
# Запуск бота в режиме наблюдения
cli-agent review-bot --watch
    [--watcher userscript|cdp]       # default: userscript
    [--auto-claim]                    # пропустить Notifier, claim по фильтру
    [--course ANDROID]                # фильтр курса (default: ANDROID)
    [--sprints 3,4,5]                 # фильтр по спринтам (пусто = все)
    [--max-consecutive 2]             # default: 2 (правило пользователя)
    [--webhook-port 8080]             # порт для приёма POST от userscript (userscript-watcher)

# Установка Tampermonkey-скрипта (печатает путь к файлу + инструкции)
cli-agent review-bot install-userscript
    # → печатает путь к yandex-messenger-watcher.user.js
    # → инструкции по установке в Tampermonkey
    # → проверяет, что WEBHOOK_URL в скрипте корректный

# Запуск Chrome в режиме remote-debugging (для --watcher cdp)
cli-agent review-bot launch-chrome
    # → запускает Chrome с --remote-debugging-port=9222 + отдельный профиль
    # → пользователь логинится в Яндекс.Мессенджере в этом профиле

# Индексация базы знаний (офлайн, перед использованием)
cli-agent review-bot index-kb
    --confluence-space ANDROID_COURSE
    --sprints 1..12
    --output ~/.local/share/review-bot/kb-index/

# Ручной режим: ревью конкретной работы (без watcher)
cli-agent review-bot review --tracker-id ANDROID-1234
    [--yes]                           # пропустить апрув (dangerous, по умолчанию off)

# Проверка конфигурации
cli-agent review-bot doctor
    # gh auth ok? tracker token ok? confluence ok? crm endpoint configured?
    # webhook port free? userscript installed? chrome debug port reachable?
```

---

## 9. Конфигурация

`~/.config/review-bot/review-bot.conf` (HOCON, секреты из env):

```hocon
watcher {
    strategy = "userscript"         # userscript | cdp
    userscript {
        port = 8080                 # порт приёмника POST /messenger-event
        path = "/messenger-event"
        dedupeWindowSec = 60
    }
    cdp {
        debugPort = 9222            # Chrome --remote-debugging-port
        tabUrlPattern = "yandex-team.ru/messenger"
    }
}

filter {
    course = "ANDROID"
    # mySprints = [3, 4, 5]         # пусто = все спринты (по ответу пользователя)
    maxConsecutive = 2
}

claim {
    crm {
        claimEndpoint = "${CLAIM_ENDPOINT}"      # из env
        method = "POST"
        authHeader = "OAuth ${CLAIM_OAUTH}"      # из env
    }
    tracker {
        token = "${TRACKER_IAM_TOKEN}"           # из env
        oauthToken = "${TRACKER_OAUTH}"          # из env
    }
    messenger {
        botToken = "${MESSENGER_BOT_TOKEN}"      # из env
    }
}

confluence {
    baseUrl = "https://wiki.yandex-team.ru"
    token = "${CONFLUENCE_TOKEN}"
    space = "ANDROID_COURSE"
}

llm {
    provider = "zai"
    model = "glm-5.1"
}

kb {
    indexPath = "~/.local/share/review-bot/kb-index/"
    embeddingModel = "nomic-embed-text"
}
```

---

## 10. Метрики (лекция нед.7 — обязательны для production)

| Метрика | Как замерять | Target |
|---------|--------------|--------|
| **Claim latency** | от появления работы до успешного claim | userscript: < 250ms, cdp: < 250ms |
| **Claim success rate** | доля клейм, не перехваченных другими | > 70% от подходящих под фильтр |
| **Review latency** (full) | от claim до отправки verdict | < 10 мин |
| **Review cost** | токены на один full review | < 30K tokens |
| **Review quality** | human rating 1-10 (точность line comments) | P80 ≥ 7 |
| **Verdict accuracy** | доля совпадений AI-вердикта с финальным решением ревьюера | > 85% |
| **Humanization quality** | human rating 1-10 на живость/понятность/наставнический тон комментариев | P80 ≥ 7 |
| **Docs linking** | доля комментариев со ссылкой на Android docs (где уместно) | > 60% от уместных |
| **LLM-tells rate** | доля комментариев с запрещёнными формулировками («Важно отметить» и пр.) | < 5% |
| **KB coverage** | доля спринтов, по которым есть индекс | 100% активных спринтов |

Baseline замеряется на первых 10–20 реальных работах.

---

## 11. Error handling & fallback

| Сценарий | Поведение |
|----------|-----------|
| **Tampermonkey не исполняется** (CSP блокирует, политика) | auto-fallback `watcher.strategy=cdp` + warning |
| **Вкладка Messenger закрыта / Chrome не запущен** (CDP watcher) | notify user: «открой вкладку/запусти Chrome», периодический retry |
| **Порт 8080 занят** (userscript receiver) | `doctor` проверяет; предложить свободный порт через `--webhook-port` |
| **Userscript не выстрелил** (API-контракт изменился) | MutationObserver внутри userscript ловит по DOM; если и он молчит → notify user + предложение реинжектить |
| **Дубликаты событий** (fetch + WS + DOM по одному сообщению) | `DedupeCache` по `messageId`/hash за окно 60с |
| **CRM claim endpoint 4xx/5xx** | retry × 3 с backoff; при провале → notify user, ручной claim |
| **Tracker API 401** (токен истёк) | notify user (для claim-стадии), не блокировать обнаружение |
| **`gh` не авторизован** | Verifier stop + понятная ошибка, не падать молча |
| **PR checkout упал** | notify, пропустить работу, не блокировать очередь |
| **`./gradlew assembleDebug` failed** | critical remark auto-REJECT (без LLM-ревью) |
| **LLM-таймаут** | retry с упрощённым промптом; 2-я попытка — cloud fallback |
| **Confluence page not found** (новый спринт) | warning, review без чеклиста (best-effort) |
| **KB index outdated** (новые материалы) | warning + команда `index-kb` подсказка |
| **Stale state** (bot crashed mid-claim) | на старте проверить `ClaimStateStore`, предложить сброс |

> **Принцип:** при любых ошибках клейм-стадии → **не молчать**. notify в терминал + desktop
> (osascript/terminal-notifier), чтобы пользователь не упустил окно перехвата. Watcher-стадия
> особенно чувствительна: бот молчит = работа ушла другому ревьюеру.

---

## 12. Human-in-the-Loop

| Стадия | HITL | Почему |
|--------|------|--------|
| Watch | ❌ нет | автоматический мониторинг |
| Notify | ✅ Enter/Esc (по умолчанию) | пользователь фильтрует сознательно (smart parity) |
| Claim | ❌ нет (после апрува) | механически |
| Collect/Verify | ❌ нет | автоматический |
| Review (AI) | ⚠️ terminal preview (опц.) | пользователь видит черновик, чтобы отсеять мусор |
| **Pending review** | ✅ **GitHub UI** | бот создаёт PENDING review (невидимый студенту); пользователь дорабатывает и сабмитит сам |
| **Verdict** | ✅ **обязательно** | dangerous op (влияет на студента); реализован через Submit review в GitHub UI |
| Post-submit (CRM/Tracker) | ⚠️ отдельная команда | после сабмита — опциональные команды для обновления CRM/Tracker, не автоматически |

> **Принцип:** вердикт и финальные комментарии — **всегда** через руки пользователя в GitHub UI
> (PENDING review flow). Это и есть HITL на критическую стадию. Бот только готовит черновик.

---

## 13. Риски и unknowns

| Риск | Mitigation |
|------|------------|
| **Этический:** автоматизация клейма нарушает политику Яндекса | пользователь сам проверяет; smart parity (только свои работы) защищаемо даже при запрете автокликеров |
| **CSP/политика блокирует Tampermonkey-инъекции** | fallback на `CdpWatcher` (ноль модификаций страницы) |
| **Yandex меняет API-контракт Messenger** | MutationObserver fallback внутри userscript (по DOM); parser regex вынесен в конфиг |
| **Messenger использует binary protobuf вместо JSON** | MutationObserver на DOM; либо LLM-парсинг тела сообщения |
| **CDP-порт недоступен** (политика, фаервол) | userscript как primary; CDP только fallback |
| **CRM endpoint меняется** | абстрактный `ClaimProvider`, конфиг-привязка; + health-check в `doctor` |
| **LLM путает Compose / View / base** | секционированный RAG-индекс + `SectionDetector` перед retrieve |
| **Confluence материалы не по всем спринтам** | best-effort: review без чеклиста + warning |
| **Токены дорогие на крупном diff** | chunk diff по файлам, каждый — отдельный LLM-вызов, batch line comments |
| **Race condition при клейме** (другой ревьюер успел) | claim-ответ 409 → notify "уже занято", пропустить |
| **Реверс-инжиниринг CRM нарушает TOS** | использовать официальный API, если есть; иначе — HTTP на endpoint кнопки (это не взлом, это стандартная автоматизация своего же рабочего процесса) |

---

## 14. Reuse strategy

| Компонент | Решение | Почему |
|-----------|---------|--------|
| `LlmClient` / `LlmClientFactory` | **Reuse** | multi-provider готов |
| `rag/` (RagIndexer, RagRetriever, StructuralChunker) | **Reuse + расширить** | для KB over course — легитимный RAG над текстом; расширить метаданными (sprint, section) |
| `memory/JsonLongTermStore` | **Reuse** | типовые ошибки, прошлые комментарии |
| `rag/embedding/OllamaEmbeddingClient` | **Reuse** | embeddings для KB |
| `mordant` / `clikt` / `JLine3` | **Reuse** | CLI-инфра |
| Watcher, Claimer, Collector, Verifier, KB-indexer, Reviewer, Reporter | **С нуля** | специфика продукта |
| `agent/swarm/` | **Нет** | MVP — один линейный pipeline |
| `agent/stage/` FSM | **Нет** | review — не многостадийная FSM-задача |
| `state/` invariants | **Нет** | overkill |

> **Вердикт:** новый модуль `:review-bot`, `implementation(project(":"))`. Переиспользуются LLM +
> RAG + memory + CLI-инфра, остальное — с нуля. Это здоровая середина: не «с нуля полностью» и не
> «переиспользовать всё».

---

## 15. Phases (roadmap)

### ✅ Demo-MVP — РЕАЛИЗОВАНО (2026-07-20)

Подмножество Phase 1, достаточное для демо. Реализовано в модуле `:review-bot`:

- ✅ **Модуль `:review-bot`** — settings.gradle.kts, build.gradle.kts (mirror support-app),
  package `com.cliagent.review` (entry `ReviewBotAppKt`).
- ✅ **CLI** (clikt): `review --pr <url> --sprint N --student "Name" [--publish] [--no-rag]`,
  `index-kb [--reset]`, `watch [--port] [--max-consecutive] [--sprints] [--auto-notify]`,
  `install-userscript [--port]`.
- ✅ **`ReviewBotFactory.fromEnv()`** — wiring по образцу `SupportAgentFactory`: z.ai (primary) /
  Ollama (fallback) через `REVIEW_PROVIDER`; embeddings всегда Ollama `nomic-embed-text`;
  изолированный RAG-индекс `~/.local/share/cli-agent/review-bot/rag/index.json`.
- ✅ **`gh` обёртка** (`GhCli`) — deadlock-safe (stdin/stdout/stderr в параллельных потоках),
  `withTimeout`, `CancellationException` rethrow, Windows `cmd.exe /c` branch.
- ✅ **`PrUrlParser`** — regex `https://github.com/{owner}/{repo}/pull/{n}` → `PrCoordinates`.
- ✅ **`PrFiles`** — `gh api .../pulls/{n}/files` через kotlinx.serialization (не regex — regex
  ломался на реальных GitHub-ответах с URL-encoded путями).
- ✅ **`DiffLineIndexer`** — парсит unified-diff `patch`, считает добавленные строки с валидными
  `(file, line, side=RIGHT)` для line-comments.
- ✅ **`PendingReviewPoster`** — `gh api --method POST .../reviews --input -` с JSON body;
  PENDING review создаётся **без** `event` поля (GitHub 422 на `"event":"PENDING"` — эмпирически).
- ✅ **`ReviewPrompt`** — свежий системный промпт: авторский шаблон (11 пунктов ✅/❌),
  humanization (mentor tone, ПОЧЕМУ не ЧТО, ссылки kotlinlang.org, запрещённые LLM-tells),
  правило «поблажки к Java-стилю» (whitelist не-идиоматичного), строгий JSON output schema.
- ✅ **`ResponseParser`** — JSON с fallback (markdown-fence stripping, balanced-brace extraction,
  fallback на empty-REJECT при полной неудаче).
- ✅ **`ChecklistParser`** — embedded `checklist-sprint-7.md` → 11 пунктов.
- ✅ **`ReviewPipeline`** — orchestration: parse URL → load diff → RAG → LLM → anchor → print
  CRM-репорт → post pending review. LLM retry на упрощённом промпте при ошибке.
- ✅ **Тесты** (47 unit-тестов): PrUrlParser, DiffLineIndexer, ChecklistParser, ResponseParser,
  MessageParser, WorkFilter. Все зелёные.
- ✅ **Demo-репозиторий** `sukhoikms27/review-bot-demo` (private):
  - PR #1 `feature/clean-vizhian` → ACCEPT (11/11 ✅, soft-рекомендации)
  - PR #2 `feature/buggy-solution` → REJECT (6 ❌, 9 line-comments, ссылки kotlinlang.org)

**Демо-прогоны review-pipeline (2026-07-20):**
```bash
CLI_AGENT_API_KEY=... REVIEW_PROVIDER=zai REVIEW_MODEL=glm-5.1 \
  ./gradlew :review-bot:run --args="review --pr https://github.com/sukhoikms27/review-bot-demo/pull/2 --sprint 7 --student Ivanov"
# → verdict=REJECT, 6 ❌, 9 line-comments, pending review создан

CLI_AGENT_API_KEY=... REVIEW_PROVIDER=zai REVIEW_MODEL=glm-5.1 \
  ./gradlew :review-bot:run --args="review --pr https://github.com/sukhoikms27/review-bot-demo/pull/1 --sprint 7 --student Petrov"
# → verdict=ACCEPT, 11/11 ✅, 9 soft-рекомендаций, pending review создан
```

### ✅ Watcher (Userscript + Webhook receiver) — РЕАЛИЗОВАНО (2026-07-22)

Источником заданий выбран DOM-перехват через Tampermonkey userscript (после F12-исследования
пользователя: Яндекс.Мессенджер использует бинарный WebSocket — fetch/WS monkey-patch отпадают).

- ✅ **Tampermonkey userscript** (`resources/yandex-messenger-watcher.user.js`):
  `MutationObserver` на `.yamb-message-content`, regex по видимому тексту, `GM_xmlhttpRequest`
  POST на `http://localhost:8082/messenger-event`. Извлекает trackerUrl (из `<a class="link_md">`),
  sprint (`[N]`), studentName (ФИО после [N]).
- ✅ **`WorkEvent` + `MessageParser`** — server-side парсинг JSON от userscript'а + дозаполнение
  `trackerId` из `trackerUrl` (regex `/([A-Z]+-\d+)`).
- ✅ **`WatchCommand` + Ktor Server** (CIO, порт 8082) — webhook receiver `/messenger-event`,
  буферизация через `Channel<WorkEvent>` между HTTP и Notifier.
- ✅ **`WorkFilter`** — дедупликация по trackerId + правило `consecutiveWorks < maxConsecutive`
  (default 2 по пользовательскому правилу) + фильтр `--sprints`.
- ✅ **`ClaimStateStore`** — persisted state (`~/.local/share/cli-agent/review-bot/claim-state.json`),
  atomic write, auto-reset после 15 мин бездействия, дедупликация последних 100 trackerIds.
- ✅ **`Notifier`** — ASCII-рамка preview (студент, спринт, tracker-link), ожидание Enter/Esc,
  печать готовой команды `review-bot review --pr ... --sprint N --student "..."`.
- ✅ **`InstallUserscriptCommand`** — распаковывает userscript в `~/review-bot-userscript/`,
  печатает пошаговые инструкции по установке в Tampermonkey.
- ✅ **Тесты** (18 unit-тестов): MessageParser (9), WorkFilter (9). Все зелёные.

**Smoke-test (2026-07-22):** `watch` запущен на :8083, POST отправлен, Notifier отрисовал точный
preview по синтетическому сообщению из реального образца пользователя:
```
║  Искандар Хамитов                                        ║
║  sprint=5  •  tracker: PCR-1989840                       ║
║  открыть: https://st.yandex-team.ru/PCR-1989840          ║
```

**Известные ограничения watcher'а:**
- ⚠️ Notifier **не запускает** pipeline автоматически даже после Enter — он печатает готовую
  команду, которую пользователь копирует. Причина: `review --pr <URL>` требует PR-ссылки,
  которая лежит внутри tracker-issue (Collector Phase 1 не реализован). Когда Collector будет
  готов, Enter сможет продолжить pipeline автоматически.
- ⚠️ Single-pending-only: если уже ждём реакции на одно задание, новое дропается (MVP-упрощение).
- ⚠️ Watcher не отменяет правила `consecutiveWorks < 2` — это notifier-only, не auto-claim.
- ⚠️ Не реализован `--auto-claim` (требует рабочего `ClaimProvider`).

### Phase 1 (full MVP, не реализовано — следующий этап)
- `ClaimProvider` (Tracker + CRM HTTP reverse-engineering) — авто-клейм вместо ручного
- `Collector` (Tracker API fetch issue → extract CRM link → extract PR link из CRM)
- `Verifier` build-стадия (`gh pr checkout` + `./gradlew build`) — встроить в pipeline
- `--auto-claim` opt-in (только после ClaimProvider)
- Интеграция Watcher → pipeline: после Enter автоматически запускать `ReviewPipeline.run(prUrl, ...)`

### Phase 2 (резервный watcher + RAG)
- `CdpWatcher` (Chrome DevTools Protocol, fallback при блокировке userscript)
- `KbIndexer` + `KbRetriever` (RAG над Confluence, секционированный)
- `SectionDetector` (auto-detect base/view/compose)
- `TypicalMistakesStore` (накопление через JsonLongTermStore)
- Desktop notifications (osascript/terminal-notifier)
- LLM-парсинг для сложных сообщений (если regex не вытягивает)

### Phase 3 (production)
- Метрики (claim latency, review quality, verdict accuracy — dashboard)
- `--auto-claim` opt-in (полный auto-claim по фильтру, без Enter)
- Maestro/Espresso для поведенческих проверок (опц.)
- Diff-анализ итераций (повторные пересдачи — что изменилось с прошлого раза)

---

## 16. Связь с концепциями недели 7

| Концепция лекции | Применение в review-bot |
|------------------|-------------------------|
| **LLM Agent как мини-ОС** | Agent проходит pipeline: watch → claim → collect → verify → review → report, как Cursor/claude-code делают свою работу |
| **Tool Registry + Executor** | `gh` (PR, comments), `TrackerClient`, `CrmClient`, `ConfluenceClient`, `KbRetriever` — все как tools |
| **AI Pipeline** | trigger: появление работы → сборка контекста → AI review → отправка артефакта |
| **Метрики** | claim latency, review cost/quality, verdict accuracy |
| **Error handling** | retry/fallback на всех стадиях: watcher, claim, verify, LLM |
| **Human-in-the-loop** | notify + verdict approval (dangerous ops) |
| **RAG над текстом** | легитимное применение — курсовая база знаний это текст |

---

## 17. Решение пользователя

> **Статус (обновлено 2026-07-20): ЧАСТИЧНО РЕАЛИЗОВАНО — demo-MVP готов.**
>
> Изначальное решение (2026-07-19): день 35 — design-only, без реализации. Пересмотрено после
> уточнения пользовательского флоу (реальная задача ревьюера Android-направления): **demo-MVP
> реализован и работает end-to-end** (см. §15 «Demo-MVP — РЕАЛИЗОВАНО»). Полный Phase 1
> (watcher/claim/CRM/Tracker) остаётся отдельной задачей.

Этот план — **основной капстоун дня 35** (реальная задача с живой болью и реальным окружением).
`day35-plan.md` (Codebase Explorer) оставлен как альтернативная концепция для сравнения.

---

## 18. Demo scope — что реально показать на демо

> **Принцип:** перехват (Watcher + Claim) — это **вся боль**, но и самое рискованное для живого
> демо. Сделать их надёжно — основная работа; на демо даём 10% времени. AI-ревью — **главная
> ценность**, ей отдаём 90% времени демо.

### 18.1 Что готово «из коробки» (Tier 1 — пара часов)

Эти части либо уже есть в проекте, либо используют публичные API без unknowns:

| Компонент | Почему готов |
|-----------|--------------|
| `gh` PR checkout + review + line comments | Публичное GitHub API, `gh` уже в проекте |
| LLM code review | `LlmClientFactory` из корня работает (z.ai/Ollama) |
| RAG indexer над Confluence/Wiki | `rag/` (RagIndexer, StructuralChunker, OllamaEmbeddingClient) готов |
| `./gradlew assembleDebug` runner | ProcessBuilder, тривиально |
| Tracker API (fetch issue, transition) | Публичный REST + IAM, есть у пользователя |
| Pending review creation | `gh api .../reviews` с `event=PENDING` — нативный GitHub |

### 18.2 Что реально за 1-2 дня (Tier 2)

| Компонент | Что нужно |
|-----------|-----------|
| `SectionDetector` (Compose/View/base) | 3-4 regex по импортам |
| `MessageParser` regex-слой | Один sample сообщения из чата (скопировать руками) |
| Confluence checklist fetcher | Один lookup ID страницы твоего спринта |
| Reviewer prompt engineering | 3-5 итераций под humanization-правила (§7.4) |
| CLI scaffolding | clikt-команда, зеркало существующих |

### 18.3 Что НЕ показывать на демо (Tier 3)

| Компонент | Почему |
|-----------|--------|
| `UserscriptWatcher` на живой странице Messenger | Чужой SPA: неизвестны URL-паттерны, формат response, race conditions — гарантированно упадёт на глазах |
| `CdpWatcher` | Требует отладки frame format + держать Chrome с debug-портом |
| CRM reverse-engineering | CORS/SameSite/CSRF — рискованно, непредсказуемо |
| `--auto-claim` | Бесполезен без рабочего claim endpoint |
| Messenger react | Бота нет в чате → невозможно |
| Полный RAG over всему Confluence | Индексация 20+ страниц — долго для демо |

### 18.4 Demo-репозиторий — Notes App (Kotlin/JVM) под своим аккаунтом

**Принцип:** демо проводится на **приватном репозитории под аккаунтом пользователя**, не на реальных
студенческих работах. Причины:
- **Этика и приватность** — реальные данные студентов не светятся на демо,
- **Предсказуемость** — контролируем ошибки, которые LLM должна поймать,
- **Авторство от своего юзера** — pending review создаётся от твоего GitHub-аккаунта.

**Тематика:** **Sprint 7 — консольное приложение «Заметки»** (чистый Kotlin/JVM, без Android SDK).
Это первый «настоящий» проект студентов после базы Java/Kotlin. Идеален для демо:
- собирается на голом JDK 21 (`./gradlew build`) — без Android SDK,
- ООП + коллекции + Scanner → классические ошибки предсказуемо ловятся,
- есть **авторское решение** (Yandex-Practicum/mobile-android/07.Sprint) и **public reference**
  (vizhian/Kotlin-Module-Project PR-1, принятый) — паттерн известен: `Screen` (abstract) +
  `ScreenHandler` (общий ввод/вывод) + `Data` + конкретные экраны по файлам.

**Reference = vizhian/Kotlin-Module-Project PR-1** (принятая работа). Используется как «clean
solution» напрямую. Оговорка: vizhian — принятая, но не «эталон от автора курса» работа; в коде
есть шероховатости (`object TopScreen` со state внутри, `NoteScreen.createElement()` с
вводящим в заблуждение именем, мёртвый `toChildren()`, циклический static import). Для демо это
**допустимо и даже полезно**: правило «поблажки к Java-стилю» (§7.4) гарантирует, что LLM относит
эти шероховатости к «рекомендациям» (блок 3, не влияет на verdict), а не к критике. Verdict по
vizhian — ACCEPT (все 11 пунктов ✅), с мягкими рекомендациями.

**Структура demo-репо (два PR — два verdict):**

```
review-bot-demo  (private, owned by user)
├── main                       ← стартовый шаблон (как у студентов в начале:
│                                 пустой Main.kt + build.gradle.kts + .gitignore)
├── PR #1: feature/clean-vizhian   ← vizhian PR-1 как есть → ожидаемо ACCEPT
│                                     (все 11 ✅, soft рекомендации в блоке 3)
└── PR #2: feature/buggy-solution  ← vizhian + 6 намеренных багов → ожидаемо REJECT
                                      (4-6 пунктов ❌, критические замечания в блоке 2)
```

Оба PR — «сдача работы» поверх одного стартового шаблона. Это близко к реальности: студенты
форкают один starter-project, у каждого свой PR. На демо прогоняем оба и показываем:
бот корректно отличает «принять» от «вернуть на переделку».

**6 намеренных ошибок** (в `feature/buggy-solution`) — каждая нарушает конкретный пункт
авторского чеклиста, чтобы LLM-ревью был предсказуемо точным:

| # | Ошибка (как в коде) | Пункт чеклиста (нарушен) | Что должно сказать ревью | Docs |
|---|---------------------|--------------------------|--------------------------|------|
| 1 | `ArchiveMenu.kt` и `NoteMenu.kt` дублируют `while(true){ printMenu(); readLine() }` вместо общего базового класса | «Нет повторений кода» | «Тут дублирование логики меню в двух классах. Задание требует вынести чтение ввода и вывод пунктов в общий код. Заведи абстрактный `Screen` + `ScreenHandler`, как в авторском решении. [Kotlin abstract classes](https://kotlinlang.org/docs/abstract-classes.html)» | kotlinlang.org |
| 2 | `Scanner(System.`in`)` создаётся заново в каждом меню (3+ раз) | «Нет повторений кода» (косвенно) | «`Scanner` создаётся заново в каждом меню — это и дублирование, и риск багов с буфером ввода. Создай один экземпляр и переиспользуй через `ScreenHandler`.» | kotlinlang.org/api/stdlib |
| 3 | `archives.add(Archive(name))` без проверки `name.isBlank()` | «Не позволяет создать архив без имени» | «Имя архива не проверяется на пустоту — можно создать архив с пустым именем, а задание это запрещает. Добавь `if (name.isBlank()) { println("Имя не может быть пустым"); return }`. [isBlank](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/is-blank.html)» | kotlinlang.org |
| 4 | `input.toInt()` без try/catch или `toIntOrNull` → `NumberFormatException` при букве | «Ошибочный ввод корректно обрабатывается» | «`toInt()` падает с `NumberFormatException`, если ввести букву. Задание требует корректной обработки. Используй `toIntOrNull() ?: { println("Введите цифру"); continue }` — это идиоматичный путь.» | kotlinlang.org |
| 5 | Весь код свалён в `Main.kt` (всё в одном файле) | «Весь код не написан в одном файле Main» | «Весь код в Main.kt — задание явно требует разделения по файлам (по одному классу на файл: Archive, Note, Menu и т.д.). Это и читаемость, и соответствие критерию.» | — |
| 6 | В `NoteMenu` нет обработки «Назад/Выход» — нельзя вернуться к архивам | «Из любого меню можно выйти на предыдущее» | «Из меню заметок нельзя вернуться к выбору архива — нет пункта выхода. Задание требует: "Из любого меню можно выйти и попасть на предыдущее". Добавь пункт "Выход" с `break`/`return parentScreen`.» | — |

Каждая ошибка:
- имеет **прямое соответствие** пункту авторского чеклиста (см. §7.4),
- должна отразиться в CRM-репорте как ❌ по соответствующему пункту,
- должна дать отдельный line-comment в GitHub PR с человеческим объяснением,
- reference-паттерн для «как правильно» — авторское решение (vizhian PR-1).

### 18.5 Demo flow (5-минутный сценарий, два PR)

**Часть A — ACCEPT (PR #1: clean-vizhian)**

```
1. [terminal] cli-agent review-bot review --pr github.com/<user>/review-bot-demo/pull/1
                                                --sprint 7 --student "Petrov"
   → bot: gh pr checkout, ./gradlew build (JVM, без Android SDK),
          RAG-search по чеклисту спринта 7 (Notes App)
2. [terminal] бот печатает CRM-репорт:
     "Привет, Petrov!
      1. Выполнение требований задания:
         ✅ Есть меню с возможностью добавления и просмотра архивов
         ✅ Есть меню с возможностью добавления и просмотра заметок
         ... (все 11 ✅)
      2. ⚠️ Критические замечания:
         Таких нет, ты молодец! Работа принята!
      3. 🍏 Рекомендации, не влияющие на результат:
         - createElement в NoteScreen ничего не создаёт — имя вводит в заблуждение
         - Scanner создаётся как object-синглтон — можно через DI, но для спринта ОК"
3. [terminal] бот создаёт PENDING review:
     → "Pending review #1 создан (0 критических, 2 soft рекомендации)"
4. [browser] открываешь PR → видишь pending review с soft-рекомендациями
5. [browser] жмёшь "Submit review" → "Approve"
   → review публикуется от твоего имени: "Отличная работа, принята!"
```

**Часть B — REJECT (PR #2: buggy-solution)**

```
1. [terminal] cli-agent review-bot review --pr github.com/<user>/review-bot-demo/pull/2
                                                --sprint 7 --student "Ivanov"
   → bot: тот же pipeline
2. [terminal] бот печатает CRM-репорт:
     "Привет, Ivanov!
      1. Выполнение требований задания:
         ❌ Нет повторений кода (ArchiveMenu/NoteMenu дублируют логику)
         ❌ Ошибочный ввод не обрабатывается (toInt без проверки)
         ❌ Из меню заметок нельзя выйти
         ❌ Архив можно создать без имени
         ❌ Весь код в одном файле
         ✅ Остальные пункты
      2. ⚠️ Критические: 5 нарушений чеклиста — вернуть на переделку
      3. 🍏 Рекомендации: Scanner создаётся в каждом меню — вынеси в ScreenHandler"
3. [terminal] бот создаёт PENDING review:
     → "Pending review #2 создан с 6 line-comments + CRM-репорт"
4. [browser] открываешь PR → видишь 6 комментариев на строках с багами
   → комментируешь вслух: «вот дублирование — бот сослался на пункт чеклиста,
      вот toInt без проверки — бот дал toIntOrNull и ссылку на kotlinlang»
5. [browser] дорабатываешь 1-2 комментария inline (демонстрация HITL)
6. [browser] жмёшь "Submit review" → "Request changes"
   → review публикуется от твоего имени, со всеми доработанными комментариями
```

**Что демонстрирует:** бот корректно отличает ACCEPT от REJECT, применяет правило поблажек (vizhian
с шероховатостями → ACCEPT, не придирается к Java-стилю), даёт очеловеченные комментарии с
ссылками на документацию.

### 18.6 Честный disclaimer в демо

В демо честно говорится:
> «Перехват сообщений из браузера — это userscript/CDP (показан в плане, требует 2-3 дня на отладку
> против реального Messenger SPA). Для демо я запускаю pipeline вручную через `review-bot review
> --pr ...`. Вся последующая обработка — реальная: GitHub API (pending review от моего аккаунта),
> LLM review с RAG по чеклисту, humanization, моя доработка и submit в GitHub UI».

Это **честно** и фокусирует внимание на ценности (AI review), а не на инфраструктуре (перехват).

### 18.7 Таймлайн подготовки

| День | Что | Результат |
|------|-----|-----------|
| **1** | Tier 1: gh wrapper, LLM review, RAG over чеклистом спринта 7. Создать `review-bot-demo` репо (Notes App: `main` = эталон, `feature/sprint-7-buggy` = 6 ошибок). Прогон pipeline локально. | Pipeline end-to-end на синтетике без Tracker/CRM |
| **2** | Tier 2: CLI scaffolding (`review-bot review --pr ... --sprint 7`), CRM-репорт по авторскому шаблону (11 пунктов ✅/❌), prompt-engineering под humanization. Pending review на demo-репо. | Полный pipeline + pending review от твоего юзера |
| **3** (если успеваешь) | Опц.: `/messenger-event` HTTP receiver + один захардкоженный sample (MessageParser regex под реальный формат сообщения). Симуляция «бот получил работу». | Полный flow от simulated-event до pending review |

**Минимум для демо:** день 1 + день 2. День 3 — bonus, если есть время.

### 18.8 Блокеры от пользователя (waiting list)

Чтобы стартовать день 1, нужно от пользователя:
1. **`gh` CLI** установлен + `gh auth login` под твоим аккаунтом → иначе не создать demo-репо и не сделать pending review от твоего юзера.
2. **z.ai API-ключ** (`CLI_AGENT_API_KEY`) — предпочтительно для качества code review. Fallback — Ollama qwen3:14b (уже работает локально).
3. **Чеклист спринта** в `.md` (либо используем авторский чеклист выше как константу).

Android SDK **не нужен** — демо на чистом Kotlin/JVM (Sprint 7: Notes App).

---

## Приложение A. Почему это лучше подходит на роль day-35, чем Codebase Explorer

| Критерий дня 35 | Codebase Explorer (v1) | Review Bot (v2, текущий) |
|-----------------|------------------------|--------------------------|
| «Реальная задача, которую хочешь автоматизировать» | синтетическая (учебная) | ✅ живая боль автора плана |
| «Использует AI» | ✅ | ✅ (LLM code review) |
| «Работает в реальном окружении» | абстрактный «любой репо» | ✅ Tracker, CRM, GitHub, Confluence, Messenger |
| «Может быть не полностью рабочим» | n/a | ✅ (CRM reverse-engineered, bot в чате недоступен — решается через userscript/CDP) |
| «Опиши, какую задачу решаешь» | ✅ | ✅ (захват + ревью студенческих работ) |
| «Покажи, как AI участвует» | ✅ | ✅ (RAG над курсом, line-by-line review) |

**Вердикт:** Review Bot точнее попадает в дух задания day-35 «реальная задача».
