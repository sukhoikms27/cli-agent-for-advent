package com.cliagent.agent.stage

/**
 * Разбор плана на шаги (гибрид execution — отдельный [StepAgent] на каждый пункт плана).
 *
 * Поддерживаемые маркеры пункта:
 *  - нумерованные: `1) ...`, `1. ...`, `1: ...`
 *  - маркированные: `- ...`, `* ...`, `• ...`
 *
 * Многострочный пункт: последующие строки без маркера (продолжение, подпункты, код)
 * присоединяются к текущему шагу до следующего маркера или пустой строки-разделителя.
 *
 * Пустые строки разделяют пункты (стандартное поведение markdown-списков).
 * Пустой/нечисловой/blanc-only план → пустой список (caller fallback'нет на весь план одним шагом).
 *
 * Чистая функция — детерминирована, покрыта unit-тестом.
 */
object PlanParser {

    private val ITEM_MARKER = Regex("""^\s*(\d+[)\.:]|[-*•])\s+(.+)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * @return список шагов (каждый — непустой текст). Пустой список если план не распознан как список.
     */
    fun parse(plan: String?): List<String> {
        if (plan.isNullOrBlank()) return emptyList()
        val steps = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) steps.add(text)
            current.clear()
        }

        plan.lineSequence().forEach { line ->
            val markerMatch = ITEM_MARKER.find(line)
            if (markerMatch != null) {
                // Новый пункт — сбрасываем накопленное
                flush()
                current.append(markerMatch.groupValues[2])
            } else if (line.isBlank()) {
                // Пустая строка — разделитель пунктов
                flush()
            } else if (current.isNotEmpty()) {
                // Продолжение текущего пункта (подпункт/код/доп. текст)
                current.append("\n").append(line.trim())
            }
            // Иначе (строка без маркера и current пуст) — пропускаем (преамбула плана)
        }
        flush()
        return steps
    }

    /**
     * Запасной режим для [ExecutionStageAgent]: если план не распался на шаги,
     * отдаём весь план одним шагом (чтобы агент всё равно отработал).
     */
    fun parseOrWhole(plan: String?): List<String> {
        val steps = parse(plan)
        return steps.ifEmpty {
            val whole = plan?.trim()?.takeIf { it.isNotEmpty() }
            listOfNotNull(whole)
        }
    }
}
