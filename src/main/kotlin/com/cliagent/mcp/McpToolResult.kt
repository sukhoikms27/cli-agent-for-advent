package com.cliagent.mcp

/**
 * Доменный результат вызова MCP-инструмента (день 17). Изолирует CLI/agent-слой от SDK-типов
 * (`CallToolResult`/`ContentBlock`): текст склеен из `TextContent`-блоков; нетекстовые блоки
 * (Image/Audio/EmbeddedResource) игнорируются в v1.
 *
 * [isError] приходит из `CallToolResult.isError` — по стандарту MCP ошибка самого tool'а
 * возвращается ВНУТРИ результата (`isError=true`), а не как exception. Протокольные ошибки
 * (tool не найден и пр.) бросаются как exception и ловятся выше.
 */
data class McpToolResult(
    val text: String,
    val isError: Boolean,
)
