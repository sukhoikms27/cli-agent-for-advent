package com.cliagent.rag

/**
 * День 24: программный детектор цитирования для пост-чека ответов и `/rag eval` (анти-галлюцинации).
 *
 * Задание дня 24 требует, чтобы модель **обязательно** возвращала источники (source + section) и
 * цитаты (фрагменты из найденных чанков). Промпт в [com.cliagent.agent.PromptBuilder] требует
 * структурированный ответ, но модель может не послушаться. Этот детектор — программная проверка
 * (defense-in-depth): анализирует ответ на наличие упоминания источников и признаков цитирования.
 *
 * **Pure function, no I/O** — легко юнит-тестируется (без моков LLM/файловой системы).
 *
 * Использование:
 * - [ContextAwareAgent.finalizeAssistant] пост-чек с warning через `logger` (не блокирует, не
 *   re-prompt — дёшево; модель может не послушаться промпт).
 * - `/rag eval` — метрика % ответов с источниками/цитатами по 10 контрольным вопросам.
 *
 * @see Result
 */
object CitationDetector {

    /**
     * Минимальная длина контента внутри кавычек, чтобы считаться цитатой (а не одиночным словом).
     * 15 символов ≈ 2-3 слова — отсекает шумные короткие совпадения.
     */
    private const val MIN_QUOTE_LEN = 15

    /**
     * Минимальная длина общего substring между ответом и текстом чанка, чтобы считать, что чанк
     * процитирован дословно. 40 символов ≈ 5-7 слов — достаточно длинная последовательность, чтобы
     * не быть случайным совпадением.
     */
    private const val MIN_OVERLAP_LEN = 40

    /**
     * Результат детекции цитирования в ответе агента.
     *
     * @property sourcesPresent true если ответ упоминает хотя бы один basename источника (из hits
     *   или expectedSources) — например «AGENTS.md» или «VectorMath.kt».
     * @property citationsPresent true если в ответе есть кавычки с контентом ≥ [MIN_QUOTE_LEN] символов
     *   ИЛИ дословный substring-overlap ≥ [MIN_OVERLAP_LEN] символов с текстом какого-то чанка.
     */
    data class Result(val sourcesPresent: Boolean, val citationsPresent: Boolean)

    /**
     * Детектирует наличие источников и цитат в [answer].
     *
     * @param answer ответ LLM (или canned-response дня 24).
     * @param hits retrieved-чанки (для сопоставления basename источника и overlap с текстом).
     *   Может быть пустым — тогда источники ищутся только в [expectedSources], overlap-проверка skipped.
     * @param expectedSources файлы, которые должны быть упомянуты (из eval-questions.json);
     *   добавляются к кандидатам помимо hits. Используется в `/rag eval`, где hits не передаются.
     */
    fun detect(
        answer: String,
        hits: List<ScoredChunk>,
        expectedSources: List<String> = emptyList(),
    ): Result {
        if (answer.isBlank()) return Result(sourcesPresent = false, citationsPresent = false)
        val lower = answer.lowercase()

        // Источники: упоминание basename любого source из hits или expectedSources.
        val sourceNames = (hits.map { basename(it.chunk.source) } + expectedSources.map(::basename))
            .filter { it.isNotBlank() }
        val sourcesPresent = sourceNames.any { name -> lower.contains(name.lowercase()) }

        // Цитаты: кавычки с контентом ≥ MIN_QUOTE_LEN, либо substring-overlap с chunk.text ≥ MIN_OVERLAP_LEN.
        val citationsPresent = hasQuotes(answer) || hits.any { overlap(answer, it.chunk.text) }

        return Result(sourcesPresent = sourcesPresent, citationsPresent = citationsPresent)
    }

    /** Базовое имя файла из пути: `"a/b/c.md"` → `"c.md"`. Пустые/blank → пусто. */
    internal fun basename(path: String): String {
        if (path.isBlank()) return ""
        val normalized = path.replace('\\', '/')
        return normalized.substringAfterLast('/')
    }

    /**
     * Есть ли в [text] кавычки («...», "...", `...`) с контентом ≥ [MIN_QUOTE_LEN] символов.
     * Учитываются парные кавычки: guillemets (ёлочки), прямые двойные, обратные апострофы (markdown).
     */
    internal fun hasQuotes(text: String): Boolean {
        // Шаблоны парных кавычек с захватом контента. Не-жадный match внутри пары.
        val patterns = listOf(
            Regex("«([^»]{${MIN_QUOTE_LEN},})»"),
            Regex("\"([^\"]{${MIN_QUOTE_LEN},})\""),
            Regex(""""([^"]{${MIN_QUOTE_LEN},})""""),
            Regex("`([^`]{${MIN_QUOTE_LEN},})`"),
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    /**
     * Есть ли общий substring длиной ≥ [MIN_OVERLAP_LEN] между [answer] и [chunkText]
     * (дословное цитирование фрагмента чанка). Линейный суффикс-подход был бы избыточен; для
     * коротких ответов CLI достаточно скользящего окна по chunkText.
     */
    internal fun overlap(answer: String, chunkText: String): Boolean {
        if (answer.length < MIN_OVERLAP_LEN || chunkText.length < MIN_OVERLAP_LEN) return false
        // Скользящее окно по chunkText: проверяем, входит ли окно длины MIN_OVERLAP_LEN в answer.
        for (i in 0..chunkText.length - MIN_OVERLAP_LEN) {
            val window = chunkText.substring(i, i + MIN_OVERLAP_LEN)
            if (answer.contains(window)) return true
        }
        return false
    }
}
