# CLI Agent

Инкрементальный CLI-агент на Kotlin для курса AI Advent Challenge #8. Растёт вместе с заданиями курса.

**Стек:** Kotlin · Gradle (Kotlin DSL) · clikt · JLine3-style REPL · Ktor · kotlinx.serialization · JSON-персистентность · z.ai (GLM-5.1, OpenAI-совместимый API).

**Текущая фаза:** Неделя 3 — stateful-агент (Days 11–15): трёхслойная память (short/working/long-term), FSM задач (clarify → planning → execution → validation → done), авто-извлечение профиля, проектные инварианты с runtime-enforcement, рой агентов (swarm: lead + workers + integrate) на каждой стадии.

Подробности архитектуры — в [`plan/global-plan.md`](plan/finisheddays/day-16/global-plan.md). Известные проблемы — в [`critical-issues.md`](plan/extensions/critical-issues.md), [`arch-issues.md`](plan/extensions/arch-issues.md), [`oop-issues.md`](plan/extensions/oop-issues.md), [`bugs.md`](plan/extensions/bugs.md).

---

## Возможности

- **REPL-чат** с историей, persistent-персистентностью (один JSON-файл на чат) и восстановлением при рестарте.
- **Контекстные стратегии** (`--context`): `sliding` (по умолч.), `facts` (sticky-facts), `summary` (авто-сжатие), `branch` (ветки диалога).
- **Стратегии рассуждения** (`-s`): `direct`, `step_by_step`, `meta_prompt`, `expert_group`.
- **Трёхслойная память** (`/memory`): short-term (диалог), working (текущая задача), long-term (knowledge/decisions/profile, кросс-чат).
- **FSM задач** (`/task`): стадии clarify → planning → execution → validation → done, артефакты стадии, переходы через `TransitionGuard` (структурная + артефактная проверки), режимы manual/plan/auto.
- **Рой агентов** (`--no-swarm` для отключения): lead + параллельные workers + integrate на каждой стадии FSM.
- **Проектные инварианты** (`--invariants`): жёсткие правила (STACK/BAN/ARCH/BUSINESS) с runtime-enforcement — отказ от нарушающих запросов и retry нарушающих ответов.
- **Профиль пользователя** (`/profile`, `--auto-profile`): style/format/about/constraints, авто-извлечение из диалога.
- **Токены и стоимость** (`/stats`, `/cost`): подсчёт токенов (~4 chars/token), оценка стоимости по прайсу моделей.
- **Markdown-рендер** и цветной вывод (mordant, `--no-color`).

---

## Сборка и запуск

### Сборка

```bash
./gradlew build          # компиляция + тесты
./gradlew test           # только тесты
./gradlew installDist    # собрать дистрибуцию для запуска (build/install/cli-agent/)
```

`installDist` создаёт готовую к запуску дистрибуцию (не fat-jar):

```
build/install/cli-agent/
├── bin/
│   ├── cli-agent          ← Unix-скрипт запуска (исполняемый)
│   └── cli-agent.bat      ← Windows
└── lib/
    └── *.jar               ← зависимости + сам проект
```

Скрипт `bin/cli-agent` сам собирает classpath из `lib/` и вызывает главный класс `com.cliagent.MainKt`.

### Запуск

```bash
# Из корня проекта
build/install/cli-agent/bin/cli-agent chat

# Алиас для удобства
alias cli-agent="$(pwd)/build/install/cli-agent/bin/cli-agent"
cli-agent chat
```

### Переменные окружения (для реальных запросов к LLM)

```bash
export CLI_AGENT_API_KEY="ваш-ключ-z.ai"                          # обязательно
export CLI_AGENT_MODEL="glm-5.1"                                  # необязательно (по умолчанию glm-5.1)
export CLI_AGENT_BASE_URL="https://api.z.ai/api/coding/paas/v4"   # необязательно
```

### Примеры команд

```bash
cli-agent chat                                   # продолжить последний чат или создать новый
cli-agent chat -c new                            # новый чат
cli-agent chat -c <chat-id>                      # конкретный чат по id
cli-agent chat -m glm-5.1 -t 0.5 -s step_by_step # своя модель/температура/стратегия рассуждения
cli-agent chat --context facts                   # контекст-стратегия: sliding/facts/summary/branch
cli-agent chat --compress --keep-recent 10       # со сжатием истории
cli-agent chat --invariants                      # включить runtime-enforcement инвариантов
cli-agent chat --no-swarm                        # последовательные stage-агенты вместо роя
cli-agent chat --auto-profile                    # авто-извлечение профиля каждые 5 ходов
cli-agent --help                                 # справка
```

Внутри REPL — slash-команды: `/help`, `/history`, `/chats`, `/stats`, `/cost`, `/summary`,
`/compress`, `/strategy`, `/branch`, `/memory`, `/profile`, `/invariants`, `/task`, `/mode`,
`/reset`, `/exit` и др. (полный список — `/help`).

### Куда пишутся данные

- Чаты и long-term память — в `$XDG_DATA_HOME/cli-agent/` (по умолчанию `~/.local/share/cli-agent/`):
  - `chats/<uuid>.json` — один файл на чат (сообщения, summary, facts, branches, workingMemory)
  - `longterm/memory.json` — глобальная долговременная память (кросс-чат)
- Конфиг (`local.properties`) читается из текущей директории запуска.
- Изолировать для тестов: `XDG_DATA_HOME=/tmp/agent-data cli-agent chat`.

### Альтернативы без installDist

```bash
./gradlew run --args="chat -c new"   # через Gradle-демон (медленнее)
./gradlew jar                        # обычный jar в build/libs/ (без зависимостей — не запускается через -jar)
```

> ⚠️ Текущий `build.gradle.kts` собирает **обычный** jar (без зависимостей), поэтому `java -jar build/libs/*.jar` не запустится. Для переносимого fat-jar нужен плагин Shadow — отдельная задача. Пока используйте `installDist` или `./gradlew run`.

---

## Архитектура

```
CLI Layer (clikt + REPL + mordant output)
        ↓
Agent Layer (ContextAwareAgent, StatefulAgent, InvariantGuard, TaskOrchestrator, stage-agents, swarm)
        ↓
┌──────────┬───────────┬───────────────────┐
│ LLM Layer│ Context   │ Memory + State    │
│ (llm/)   │ (context/)│ (memory/ + state/)│
└──────────┴───────────┴───────────────────┘
        ↓
Infrastructure (JSON files, Ktor, Config)
```

Ключевые интерфейсы: `LlmClient` (impl `OpenAiCompatibleClient`), `ContextStrategy` (4 impl),
`MemoryStore` (impl `JsonChatStore`), `Agent` (+ decoration: `StatefulAgent`, `InvariantGuard`),
`StageAgent` (5 stage-impl + `SwarmStageAgent`), `InvariantChecker` (impl `LlmInvariantChecker`).

Структура пакетов и фазы развития — в [`CLAUDE.md`](CLAUDE.md) и [`plan/global-plan.md`](plan/finisheddays/day-16/global-plan.md).
