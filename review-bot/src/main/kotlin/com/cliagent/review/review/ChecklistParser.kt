package com.cliagent.review.review

/**
 * Парсит embedded чеклист `.md` в список пунктов.
 *
 * Формат `.md` (см. `resources/checklist-sprint-7.md`):
 * ```
 * 1. **Текст пункта.**
 *    Продолжение описания.
 * 2. **Другой пункт.**
 *    ...
 * ```
 *
 * Правило: каждая нумерованная строка (`^\d+\.\s`) начинает новый пункт. Текст пункта — конкатенация
 * строки с номером и последующих line-wrapped строк (до следующего номера). Markdown-разметка
 * (звёздочки `**`, обратные кавычки) вычищается.
 *
 * @return список пунктов чеклиста (только текст, без оценки — оценка ставится LLM).
 */
object ChecklistParser {

    /**
     * @param mdContent текст .md файла.
     * @return список текстов пунктов (без leading/trailing whitespace).
     */
    fun parse(mdContent: String): List<String> {
        val items = mutableListOf<MutableList<String>>()
        val numbered = Regex("""^\s*\d+\.\s+(.*)""")
        mdContent.lineSequence().forEach { raw ->
            val m = numbered.find(raw)
            if (m != null) {
                // Новый пункт.
                items.add(mutableListOf(m.groupValues[1]))
            } else if (items.isNotEmpty()) {
                // Продолжение предыдущего пункта (line wrap). Пустые строки игнорируем, но
                // служат разделителем между пунктами в исходном markdown — не добавляем их.
                val text = raw.trim()
                if (text.isNotEmpty()) items.last().add(text)
            }
        }
        return items.map { lines -> cleanMarkdown(lines.joinToString(" ")) }
            .filter { it.isNotBlank() }
    }

    /** Вычищает markdown-разметку: `**bold**` → `bold`, `` `code` `` → `code`. */
    private fun cleanMarkdown(s: String): String =
        s.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
