# День 6. Первый агент

## Задание курса

Реализуйте простого агента, который:
- принимает запрос пользователя
- отправляет его в LLM через API
- получает ответ
- выводит результат в вашем интерфейсе

**Важно:**
- агент должен быть отдельной сущностью, а не просто один вызов API
- логика запроса и ответа должна быть инкапсулирована в агенте

**Результат:** Агент принимает запрос и корректно вызывает LLM через API

---

## Что уже есть (после дней 1–5)

```
✅ LlmClient интерфейс + OpenAiCompatibleClient (Ktor)
✅ ChatRequest с параметрами, ChatResponse, ChatMessage
✅ SystemPrompts, ReasoningStrategy, PromptTemplates
✅ LlmResult sealed class
✅ AppConfig + ConfigRepository
✅ BenchmarkRunner (утилита)
✅ Main.kt — разрозненные демонстрации каждого дня
```

## Что меняется в этот день

**Крупный архитектурный шаг:** вводим слой `Agent` и слой `CLI (clikt)`.
До сих пор Main.kt был простым скриптом. Теперь:
1. Агент — отдельная сущность, инкапсулирующая логику чата
2. CLI — REPL-режим через clikt для интерактивного общения

Это структурообразующий день — дальше все фичи надстраиваются поверх агента.

---

## Что реализуем

### 1. Интерфейс агента

**Файл:** `src/.../agent/Agent.kt` (новый)

```kotlin
interface Agent {
    suspend fun chat(userMessage: String): String
    fun getHistory(): List<ChatMessage>
    fun reset()
}
```

> Три метода — минимум для агента. `chat()` — основной, `getHistory()` нужен
> для отображения контекста (день 7+), `reset()` — для очистки сессии.

### 2. SimpleAgent

**Файл:** `src/.../agent/SimpleAgent.kt` (новый)

```kotlin
class SimpleAgent(
    private val llmClient: LlmClient,
    private val model: String,
    private val systemPrompt: ChatMessage = SystemPrompts.default,
    private val reasoningStrategy: ReasoningStrategy? = null
) : Agent {

    private val history = mutableListOf<ChatMessage>()

    override suspend fun chat(userMessage: String): String {
        // 1. Добавить сообщение пользователя в историю
        val userMsg = ChatMessage(role = "user", content = userMessage)
        history.add(userMsg)

        // 2. Собрать сообщения для запроса
        val messages = buildMessages()

        // 3. Отправить запрос
        val request = ChatRequest(model = model, messages = messages)
        val result = llmClient.chat(request)

        // 4. Обработать ответ
        val responseText = when (result) {
            is LlmResult.Success -> {
                val assistantMsg = result.data.choices.first().message
                history.add(assistantMsg)
                assistantMsg.content
            }
            is LlmResult.Error -> "Error: ${result.code} — ${result.message}"
        }

        return responseText
    }

    private fun buildMessages(): List<ChatMessage> {
        val system = if (reasoningStrategy != null) {
            PromptTemplates.buildSystemMessage(reasoningStrategy)
        } else {
            systemPrompt
        }
        return listOf(system) + history.toList()
    }

    override fun getHistory(): List<ChatMessage> = history.toList()

    override fun reset() {
        history.clear()
    }
}
```

> **Ключевой принцип:** история хранится ВНУТРИ агента. CLI не управляет историей —
> он только передаёт пользовательский ввод и получает ответ.
> Это инкапсуляция, которую требует курс.

> **ReasoningStrategy** — опциональный параметр. Если задан, перекрывает systemPrompt.
> Это позволяет использовать стратегии из дня 3 через агента.

### 3. CLI на clikt — REPL-режим

**Файл:** `src/.../cli/CliAgentCommand.kt` (новый)

```kotlin
class CliAgentCommand : CliktCommand(name = "cli-agent") {
    override fun run() {
        // Корневая команда — выводит help или запускает чат по умолчанию
    }
}
```

**Файл:** `src/.../cli/ChatCommand.kt` (новый)

```kotlin
class ChatCommand : CliktCommand(name = "chat", help = "Start interactive chat") {
    private val model by option("-m", "--model", help = "Model name").default("glm-5.1")
    private val temperature by option("-t", "--temperature", help = "Temperature").double().default(0.7)
    private val strategy by option("-s", "--strategy", help = "Reasoning strategy").default("direct")

    override fun run() = runBlocking {
        val config = ConfigRepository().load()
        val client = OpenAiCompatibleClient(config.baseUrl, config.apiKey)

        // Создаём агента с параметрами из CLI
        val reasoningStrategy = ReasoningStrategy.entries.find { it.label == strategy }
        val agent = SimpleAgent(
            llmClient = client,
            model = model,
            reasoningStrategy = reasoningStrategy
        )

        println("CLI Agent v0.1 | Model: $model | Strategy: $strategy")
        println("Type /help for commands, /exit to quit\n")

        // REPL-цикл
        while (true) {
            print("> ")
            val input = readLineOrNull() ?: break
            if (input.isBlank()) continue

            when {
                input == "/exit" -> break
                input == "/help" -> printHelp()
                input == "/history" -> printHistory(agent)
                input == "/reset" -> { agent.reset(); println("History cleared.") }
                else -> {
                    val response = agent.chat(input)
                    println("\n$response\n")
                }
            }
        }
    }

    private fun printHelp() {
        println("""
            |/help     — Show this help
            |/history  — Show chat history
            |/reset    — Clear chat history
            |/exit     — Exit the program
        """.trimMargin())
    }

    private fun printHistory(agent: Agent) {
        agent.getHistory().forEachIndexed { i, msg ->
            println("[${i + 1}] ${msg.role}: ${msg.content.take(100)}...")
        }
    }
}
```

> **REPL-команды** — начинаем с минимума: /help, /history, /reset, /exit.
> В день 7+ добавятся /context, /stats и другие.

### 4. Точка входа

**Файл:** `src/.../Main.kt` — переписать

```kotlin
fun main(args: Array<String>) = CliAgentCommand()
    .subcommands(ChatCommand())
    .main(args)
```

> Теперь Main.kt — тонкая обёртка над clikt. Вся логика в командах.

### 5. Зависимость clikt

**Файл:** `build.gradle.kts` — добавить

```kotlin
dependencies {
    implementation("com.github.ajalt.clikt:clikt:<version>")
    // ... остальные зависимости
}
```

---

## Изменения в существующих файлах

| Файл | Изменение |
|---|---|
| `build.gradle.kts` | Добавить зависимость clikt |
| `Main.kt` | Полностью переписать — делегирование clikt |

## Новые файлы

| Файл | Описание |
|---|---|
| `agent/Agent.kt` | Интерфейс агента |
| `agent/SimpleAgent.kt` | Базовый агент с историей в памяти |
| `cli/CliAgentCommand.kt` | Корневая команда clikt |
| `cli/ChatCommand.kt` | REPL-режим чата |

---

## На что обратить внимание

1. **runBlocking в ChatCommand.run()** — clikt вызывает `run()` синхронно.
   Для suspend-вызовов используем `runBlocking`. Это нормально для CLI.
   Альтернатива — `CliktCommand.run()` не suspend, поэтому оборачиваем.

2. **readLineOrNull()** — нужно правильно обработать EOF (Ctrl+D) и пустой ввод.

3. **Агент хранит историю в памяти** — при перезапуске история теряется.
   Это ожидаемо — персистентность появится в день 7.

4. **Демонстрационные Main.kt из дней 1–5** — они заменены на clikt.
   Если нужно сохранить — можно вынести в `src/.../demos/` или удалить.
   Рекомендация: удалить, чтобы не было мёртвого кода.

5. **Subcommand vs аргумент** — `cli-agent chat` запускает REPL.
   Позже добавятся `cli-agent config`, `cli-agent context` и т.д.

6. **Цветной вывод** — clikt поддерживает `echo()` с ANSI-стилями через `CliktConsole`.
   Можно добавить цвета для ответов агента, но не критично на этом этапе.

---

## Критерии проверки

- [ ] `./gradlew run --args="chat"` запускает REPL-режим
- [ ] В REPL можно отправлять сообщения и получать ответы
- [ ] `/history` показывает историю текущей сессии
- [ ] `/reset` очищает историю
- [ ] `/exit` завершает программу
- [ ] `--model`, `--temperature`, `--strategy` флаги работают
- [ ] Агент — отдельный класс (SimpleAgent), не Main.kt
- [ ] История хранится внутри агента

---

## Состояние проекта после дня 6

```
✅ Всё из дней 1–5
✅ Agent интерфейс + SimpleAgent — агент как отдельная сущность
✅ clikt CLI — REPL-режим с командами /help, /history, /reset, /exit
✅ Main.kt — делегирование clikt
✅ Параметры чата через CLI-флаги (model, temperature, strategy)
❌ Персистентность истории (день 7)
❌ Подсчёт токенов (день 8)
```

---

## Что изменилось после сравнения с Android-реализацией

> Подробности: `plan/changelog-android-diff.md`

| Изменение | Тип | Описание |
|---|---|---|
| ~~`AgentFactory`~~ | REJECTED | Android использует фабрику с Hilt DI. В CLI агент создаётся один раз в ChatCommand.run() — фабрика = обёртка над конструктором без добавленной стоимости. К тому же фабрика принимала бы GenerationConfig, который мы не создаём |
