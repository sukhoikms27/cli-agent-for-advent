# T8 — Ручная верификация

## Цель
Подтвердить end-to-end: MCP-соединение устанавливается, `listTools` корректно возвращается и
выводится, ресурсы (subprocess) корректно освобождаются.

## Требования
Node.js + `npx` (для запуска reference-сервера `@modelcontextprotocol/server-filesystem`).
Если `npx` недоступен — альтернатива: Python MCP-сервер (`pip install "mcp[cli]"; mcp run server.py`).

## Команда запуска
```bash
./gradlew run --args="chat"
```

## Сценарии

### 1. Inline override (основной)
В REPL:
```
/mcp
/mcp list-tools npx -y @modelcontextprotocol/server-filesystem /tmp/agent-mcp-test
```
**Ожидается:** спиннер «Connecting to MCP server…», затем mordant-таблица инструментов
(`read_file`, `write_file`, `list_directory`, `create_directory`, `move_file`, `search_files`,
`get_file_info`, `list_allowed_directories`). REPL продолжает работать.

### 2. Через `local.properties`
В `local.properties` (в корне проекта):
```
mcp.command=npx -y @modelcontextprotocol/server-filesystem /tmp/agent-mcp-test
```
REPL: `/mcp` → «configured (local.properties mcp.command)»; `/mcp list-tools` → таблица.

### 3. Через env
```bash
CLI_AGENT_MCP_COMMAND="npx -y @modelcontextprotocol/server-filesystem /tmp/agent-mcp-test" ./gradlew run --args="chat"
```
`/mcp` → «configured (env CLI_AGENT_MCP_COMMAND)»; `/mcp list-tools` → таблица.

### 4. Негативный (битый бинар)
```
/mcp list-tools nonexistent-bin-xyz
```
**Ожидается:** `MCP failed: ...` (Cannot run program / IOException), REPL жив, можно вводить дальше.

### 5. Таймаут
Запустить сервер, который не отвечает на handshake (напр. `/mcp list-tools sleep 60`):
**Ожидается:** через 30с — `MCP failed: MCP server did not respond within 30s`, REPL жив.

### 6. Cleanup (нет зомби)
После каждого успешного сценария:
```bash
ps aux | grep -c server-filesystem
```
**Ожидается:** 0 (или baseline) — `stopProcess` (`destroy` → `waitFor(2s)` → `destroyForcibly`)
корректно убивает subprocess.

## Verify
Все 6 сценариев пройдены. Результаты зафиксировать в commit-message T8 (или notes).
Если `npx` first-run качает пакет долго — спиннер «Connecting…» это покрывает (UX виден).

## Коммит
`docs: day16 manual verification against server-filesystem (day16 T8)` — commit с результатами
(или amend в финальный день-16 коммит).
