package com.cliagent.review.review

/**
 * Полный результат ревью.
 *
 * @property studentName имя студента (для «Привет, X!»).
 * @property checklist 11 пунктов чеклиста с ✅/❌.
 * @property criticalRemarks критические замечания (блокирующие приём).
 * @property recommendations soft-рекомендации (не влияют на verdict).
 * @property lineComments line-by-line комментарии для GitHub PR.
 * @property verdict ACCEPT (все ✅, нет critical) / REJECT (есть ❌ или critical).
 */
data class ReviewResult(
    val studentName: String,
    val checklist: List<ChecklistItem>,
    val criticalRemarks: List<String>,
    val recommendations: List<String>,
    val lineComments: List<ReviewComment>,
    val verdict: VerdictProposal,
) {
    /** Короткое summary для body review в GitHub. */
    val summary: String
        get() = when (verdict) {
            VerdictProposal.ACCEPT -> "✅ Работа принята. Все пункты чеклиста выполнены."
            VerdictProposal.REJECT -> "❌ Работа возвращена на доработку. См. критические замечания."
        }
}

/**
 * Один пункт чеклиста с оценкой.
 *
 * @property text точная формулировка (из embedded checklist).
 * @property passed true = ✅, false = ❌.
 * @property evidence на чём основано решение (файл:строка или короткое описание) — для прозрачности.
 */
data class ChecklistItem(
    val text: String,
    val passed: Boolean,
    val evidence: String? = null,
)

/**
 * Line-comment для GitHub PR.
 *
 * @property file путь файла (relative to repo root).
 * @property line номер строки в новой (head) версии файла.
 * @property side всегда [CommentSide.RIGHT] для нашего use-case (комментируем добавленный код).
 * @property body markdown текст комментария (очеловеченный, на русском).
 * @property anchorHint подсказка для анкоринга: паттерн для поиска строки, если [line]=0 или невалиден.
 */
data class ReviewComment(
    val file: String,
    val line: Int,
    val side: CommentSide = CommentSide.RIGHT,
    val body: String,
    val anchorHint: String? = null,
)

/** Сторона diff для line-comment. Мы всегда комментируем добавленный код → RIGHT. */
enum class CommentSide { LEFT, RIGHT }

/** Финальный вердикт по работе. */
enum class VerdictProposal { ACCEPT, REJECT }
