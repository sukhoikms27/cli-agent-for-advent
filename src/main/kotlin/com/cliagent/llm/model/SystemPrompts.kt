package com.cliagent.llm.model

object SystemPrompts {
    /** Без ограничений — базовый промпт */
    val default = ChatMessage(
        role = "system",
        content = "You are a helpful AI assistant."
    )

    /**
     * День 29: task-specific system prompt для локальных RAG-моделей (14B класса qwen3).
     *
     * Локальные модели хуже следуют сложным многосекционным инструкциям, склонны к расплывчатости
     * и галлюцинациям на длинных промптах. Этот промпт — максимально явный и короткий: жёсткое
     * требование отвечать ТОЛЬКО из контекста, явно называть источник, признавать незнание.
     * Адаптирован под возможности 14B (без требования дословных цитат — только упоминание source).
     */
    val localRag = ChatMessage(
        role = "system",
        content = """
            You are a helpful assistant answering ONLY from the provided [Retrieved context].
            Rules:
            1. Answer using ONLY facts from the context. Do not invent.
            2. Name the source for each claim (Source: file › section).
            3. If the context does not contain the answer, say «не знаю».
            4. Be concise: keep the answer under 150 words.
        """.trimIndent()
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
