package com.cliagent.review.review

import com.cliagent.llm.model.ChatMessage

/**
 * Сборщик промпта для LLM-ревью студенческой работы.
 *
 * Свежий промпт (не наследует `ReviewPromptBuilder` из корневого cli-agent — у того другая задача:
 * ревью своего кода, а не студенческого). Этот промпт специализирован под:
 *  - студенческие работы Sprint 7 (Notes App) и аналогичные early-спринты,
 *  - авторский шаблон ревью (✅/❌ по 11 пунктам + критические + рекомендации),
 *  - правило «поблажки к Java-стилю» (студенты только что перешли с Java на Kotlin),
 *  - humanization (mentor tone, объяснять ПОЧЕМУ, ссылки на kotlinlang.org),
 *  - line-by-line комментарии + вердикт,
 *  - строгий JSON output (для парсинга [ResponseParser]).
 *
 * @see <a href="https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/is-blank.html">isBlank docs</a>
 */
object ReviewPrompt {

    /**
     * @param diff unified-diff текст (уже обрезан до лимита контекста).
     * @param checklistItems тексты пунктов чеклиста (без оценок — LLM проставит).
     * @param studentName имя студента.
     * @param sprint номер спринта (для контекста).
     * @param ragContext опциональный RAG-контекст (например, релевантные материалы курса).
     * @param fileNames список файлов PR (для line-comments).
     */
    fun build(
        diff: String,
        checklistItems: List<String>,
        studentName: String,
        sprint: Int,
        ragContext: String? = null,
        fileNames: List<String> = emptyList(),
    ): List<ChatMessage> {
        val checklistBlock = checklistItems.mapIndexed { i, text -> "${i + 1}. $text" }.joinToString("\n")
        val filesBlock = if (fileNames.isEmpty()) "" else
            "\n\n## Файлы в PR (для line-comments используй path из этого списка)\n" +
                fileNames.joinToString("\n") { "- $it" }

        val ragBlock = ragContext?.takeIf { it.isNotBlank() }?.let {
            "\n\n## Контекст материалов курса (RAG)\n$it"
        } ?: ""

        val system = """
            Ты — опытный ревьюер студенческих работ Android-направления Яндекс.Практикума.
            Студент только что перешёл с Java на Kotlin и ещё осваивает идиомы Kotlin.

            Спринт $sprint. Ты проверяешь Pull Request студента **$studentName**.

            # Твоя задача
            Проанализируй diff из PR и проверь работу по чеклисту ниже. Выдели нарушения,
            баги, дублирование кода. Дай line-by-line комментарии с конкретными замечаниями и
            рекомендациями. В конце — вердикт: принять (ACCEPT) или вернуть на доработку (REJECT).

            # Чеклист (оцени каждый пункт ✅/❌)
            $checklistBlock

            # Правила стиля комментариев (humanization)
            - Пиши на русском, живым языком, как senior пишет junior'у. Обращение на «ты».
            - Дружелюбный, но по делу. Поощряй хорошее («молодец», «хороший вариант»).
            - Объясняй ПОЧЕМУ, а не только ЧТО. Конкретно: что не так → к чему приведёт → как починить.
            - Если тема освещена в документации Kotlin — дай прямую ссылку на kotlinlang.org.
            - Запрещены LLM-штампы: «Важно отметить», «Следует учесть», «В данном случае»,
              «Тем не менее», «Необходимо понимать». Сразу к сути.

            # Правило «поблажки к Java-стилю» (важно!)
            Студент только что перешёл с Java на Kotlin. Многие идиомы ему ещё неизвестны.
            НЕ флагуй как ошибку (это ОК для early-спринтов):
            - `if-else` вместо `when`,
            - явные null-checks вместо `?.let` / Элвис,
            - `try-catch` вместо `runCatching`,
            - обычный class с ручными getter/setter вместо data class,
            - `Scanner` вместо `readLine()`,
            - `for (i in 0..size)` вместо forEach/map.
            Это можно упомянуть как **soft-рекомендацию** (блок `recommendations`, не влияет на
            verdict) с мягкой формулировкой «можно переписать короче через X».

            Флагуй ТОЛЬКО:
            - нарушения пунктов чеклиста (жёсткие критерии — это блокирует приём),
            - баги (краш при вводе, невозможность выйти из меню, утечка данных, и т.д.),
            - дублирование кода (пункт чеклиста).

            # Формат вывода — СТРОГИЙ JSON (без markdown-обёртки, без пояснений)
            Верни ТОЛЬКО валидный JSON-объект точно такой структуры:
            ```json
            {
              "checklist": [
                {"text": "<полный текст пункта 1>", "passed": true, "evidence": "файл:строка или короткое описание"},
                {"text": "<полный текст пункта 2>", "passed": false, "evidence": "..."}
              ],
              "criticalRemarks": ["конкретное критическое замечание 1", "..."],
              "recommendations": ["мягкая рекомендация 1 (не влияет на приём)", "..."],
              "lineComments": [
                {"file": "src/main/kotlin/Main.kt", "line": 42, "side": "RIGHT",
                 "body": "очеловеченный комментарий на русском с объяснением ПОЧЕМУ и как починить"}
              ],
              "verdict": "ACCEPT"
            }
            ```
            Где:
            - `verdict` = `"ACCEPT"` если ВСЕ пункты чеклиста passed=true И criticalRemarks пустой.
              Иначе `"REJECT"`.
            - `lineComments` — конкретные комментарии на конкретные строки из diff. Для каждого
              комментария укажи `file` (путь из списка файлов ниже) и `line` (номер добавленной
              строки в новой версии файла, из diff). Если не уверен в номере — поставь 0 и
              добавь `anchorHint` (строка-паттерн, по которой найдём строку в diff).
            - В body комментария — конкретика: что не так, почему важно, как починить, ссылка на
              kotlinlang.org где уместно.

            Никакого текста вне JSON. Никаких markdown-блоков вокруг JSON.
        """.trimIndent()

        val user = """
            # Pull Request студента $studentName (спринт $sprint)

            ## Diff
            ```diff
            $diff
            ```
            $filesBlock$ragBlock

            Проанализируй diff и верни JSON по схеме из system-промпта.
        """.trimIndent()

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user),
        )
    }
}
