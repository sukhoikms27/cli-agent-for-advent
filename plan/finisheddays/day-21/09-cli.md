# 09 — CLI команды (`cli/RagCommands.kt`, правки `ChatCommand.kt`)

`RagCommands(config: RagConfig)` — обработчик `/rag`, зеркалит структуру `handleMcp`.

## Команды
| Команда | Действие |
|---|---|
| `/rag` | Сводка: статус RAG, модель эмбеддинга, текущий индекс |
| `/rag index [fixed\|structural]` | Переиндексация корпуса (chunking + embeddings → JSON) |
| `/rag stats [fixed\|structural]` | Статистика индекса (mordant-таблица: чанки, токены, размерность) |
| `/rag compare` | Оба индекса + сравнительная таблица + пробный retrieval |
| `/rag search <query>` | Probe retrieval top-5 (smoke-test, **без** агента — инъекция = день 22) |
| `/rag config` | Показать RagConfig |

## Wiring в ChatCommand.kt
- `val ragCommands = RagCommands(config.rag)` — после загрузки config.
- Status-line += `RAG: ON|OFF` (по образцу `MCP: N server(s)`).
- REPL `when`-блок: `input.startsWith("/rag") -> ragCommands.handle(input)`.
- `/help` += блок `/rag`-команд + note про Ollama.

## Граница дня 21
**Слой агента (`PromptBuilder`, `buildMessagesToSend`) НЕ трогаем** — инъекция `[Retrieved context]`
это день 22. `/rag search` — пробный retrieval в изоляции, не влияет на чат.

## Деградация
Без Ollama: `/rag index`/`compare`/`search` показывают понятное сообщение об ошибке
(`Ollama недоступна… ollama serve && ollama pull nomic-embed-text`); остальной агент не страдает.
Embedder создаётся лениво (не на старте REPL) → `/rag config`/`stats` работают офлайн.
