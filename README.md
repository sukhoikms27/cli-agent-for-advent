# CLI Agent

Инкрементальный CLI-агент на Kotlin для курса AI Advent Challenge #8. Растёт вместе с заданиями курса.

**Стек:** Kotlin · Gradle (Kotlin DSL) · clikt · JLine3-free REPL · Ktor · kotlinx.serialization · JSON-персистентность · z.ai (GLM-5.1, OpenAI-совместимый API).

**Текущая фаза:** Неделя 3 — stateful-агент (Days 11–13). Day 11 — модель памяти (short/working/long-term layers).

Подробности архитектуры — в [`plan/global-plan.md`](plan/global-plan.md).

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
cli-agent chat                       # продолжить последний чат или создать новый
cli-agent chat -c new                # новый чат
cli-agent chat -c <chat-id>          # конкретный чат по id
cli-agent chat -m glm-5.1 -t 0.5 -s step_by_step   # своя модель/температура/стратегия рассуждения
cli-agent chat --context facts       # контекст-стратегия: sliding(по умолчанию)/facts/summary/branch
cli-agent chat --compress --keep-recent 10         # со сжатием истории
cli-agent --help                     # справка
```

Внутри REPL — slash-команды: `/help`, `/history`, `/stats`, `/cost`, `/strategy`, `/branch`, `/memory`, `/reset`, `/exit` и др.

### Куда пишутся данные

- Чаты и long-term память — в `$XDG_DATA_HOME/cli-agent/` (по умолчанию `~/.local/share/cli-agent/`):
  - `chats/<uuid>.json` — один файл на чат (сообщения, summary, facts, branches, workingMemory)
  - `longterm/memory.json` — глобальная долговременная память (кросс-чат)
- Изолировать для тестов: `XDG_DATA_HOME=/tmp/agent-data cli-agent chat`.

### Альтернативы без installDist

```bash
./gradlew run --args="chat -c new"   # через Gradle-демон (медленнее)
./gradlew jar                        # обычный jar в build/libs/ (без зависимостей — не запускается через -jar)
```

> ⚠️ Текущий `build.gradle.kts` собирает **обычный** jar (без зависимостей), поэтому `java -jar build/libs/*.jar` не запустится. Для переносимого fat-jar нужен плагин Shadow — отдельная задача. Пока используйте `installDist` или `./gradlew run`.
