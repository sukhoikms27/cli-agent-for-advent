package com.cliagent.llm.model

object SystemPrompts {
    /** Без ограничений — базовый промпт */
    val default = ChatMessage(
        role = "system",
        content = "You are a helpful AI assistant."
    )

    /** С ограничением формата — JSON */
    val jsonFormat = ChatMessage(
        role = "system",
        content = """
            You are a helpful AI assistant.
            You MUST respond in JSON format with the following structure:
            {"answer": "your answer here", "confidence": 0.0-1.0}
            Do not include any text outside the JSON object.
        """.trimIndent()
    )

    /** С ограничением длины */
    fun withMaxLength(maxWords: Int) = ChatMessage(
        role = "system",
        content = """
            You are a helpful AI assistant.
            Your response must be no longer than $maxWords words.
            Be concise and direct.
        """.trimIndent()
    )

    /** С stop sequence */
    val withStopSequence = ChatMessage(
        role = "system",
        content = """
            You are a helpful AI assistant.
            End your response with ===END=== when you are done.
        """.trimIndent()
    )
}
