package com.cliagent.rag.rewrite

/**
 * День 23: heuristic query rewrite. Чистая функция (без IO), детерминированная —
 * нормализует запрос для семантического поиска:
 *  1. lowercase, trim;
 *  2. схлопывание повторяющихся пробелов;
 *  3. удаление RU+EN стоп-слов (местоимения, вопросительные, предлоги — не несут семантики);
 *  4. удаление пунктуации (эмбеддер `nomic-embed-text` чувствителен к чистому тексту).
 *
 * Идея: эмбеддер «Какие стратегии есть в проекте?» и «стратегии проекте» должны дать
 * близкие векторы; убирая шум, мы снижаем вклад нерелевантных токенов в косинусное сходство.
 *
 * Мягкая деградация: пустой/только-из-стоп-слов запрос → пустая строка (эмбеддер вернёт
 * нейтральный вектор; [com.cliagent.rag.RagRetriever] это переживёт). Не выбрасывает.
 */
class HeuristicQueryRewriter : QueryRewriter {
    override val name: String = "heuristic"

    override suspend fun rewrite(query: String): String = normalize(query)

    companion object {
        /**
         * Стоп-слова RU+EN — частотный минимум (местоимения, вопросительные, предлоги, союзы).
         * Терминов предметной области здесь НЕТ (они несут семантику → остаются).
         */
        private val STOP_WORDS: Set<String> = setOf(
            // EN
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "do", "does", "did", "have", "has", "had", "will", "would", "can", "could",
            "of", "in", "on", "at", "to", "for", "with", "by", "from", "and", "or", "not",
            "this", "that", "these", "those", "it", "its", "what", "which", "who", "whom",
            "how", "when", "where", "why", "there", "here", "about", "into", "as",
            // RU
            "и", "в", "во", "на", "по", "для", "с", "со", "к", "от", "до", "о", "об", "при",
            "что", "как", "какой", "какая", "какие", "каких", "кто", "чем", "чего", "где",
            "когда", "почему", "зачем", "это", "этот", "эта", "эти", "тот", "та", "те",
            "есть", "был", "была", "были", "быть", "он", "она", "они", "мы", "вы", "я",
            "не", "ни", "но", "или", "же", "ли", "бы", "тоже", "также", "так", "если",
            "проекта", "проекте", "проект" // слишком общий термин корпуса → убираем шум
        )

        /** Видимая точка расширения/тестирования — нормализация без инстанса. */
        fun normalize(rawQuery: String): String {
            // lowercase (locale-independent — Java String.lowercase() без Locale может ломать 'I/ı')
            val lowered = rawQuery.lowercase()
            // заменить любую пунктуацию на пробел (кроме дефиса внутри слов — оставляем терминам)
            val noPunct = lowered.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            // токенизация по пробелам → фильтр стоп-слов → схлопывание пробелов
            return noPunct.split(Regex("\\s+"))
                .filter { it.isNotBlank() && it !in STOP_WORDS }
                .joinToString(" ")
                .trim()
        }
    }
}
