package com.cliagent.review

import com.cliagent.review.cli.MainCli

/**
 * День 35 (capstone недели 7) — **Review Bot**: автоматизация ревью студенческих заданий
 * (Android-направление, Yandex Practicum).
 *
 * MVP — демо-режим: `review --pr <url> --sprint N --student "Name"` прогоняет pipeline
 * (load diff → RAG → LLM review → создаёт PENDING review через `gh` CLI от юзера) и
 * печатает CRM-репорт в авторском формате (✅/❌ по 11 пунктам чеклиста + критические + рекомендации).
 *
 * **Диспетчер подкоманд** (clikt):
 *  - `review` — основной pipeline ревью PR.
 *  - `index-kb` — индексация embedded чеклиста в RAG-индекс (запускать перед `review`, один раз).
 *
 * Сборка/запуск:
 * ```
 * ./gradlew :review-bot:run --args="index-kb"
 * ./gradlew :review-bot:run --args="review --pr https://github.com/<owner>/<repo>/pull/<n> --sprint 7 --student Ivanov"
 * ```
 *
 * См. [ReviewBotFactory.fromEnv] для env-переменных (LLM/RAG провайдеры).
 */
fun main(args: Array<String>) {
    MainCli().main(args)
}
