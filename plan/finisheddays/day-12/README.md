# День 12. Персонализация ассистента — задачи

## Задание курса (`plan/newdays/day12.md`)
Добавить персонализацию поверх модели памяти: создать профиль пользователя, описать предпочтения (стиль, формат, ограничения), подключить профиль к каждому запросу. Проверить: ответы для разных профилей; что ассистент учитывает автоматически. Артефакт: персонализированный агент.

## Что уже есть после Day 11 (фон)
- `UserProfile(style, format, constraints)` — stub в `memory/MemoryLayer.kt`.
- `LongTermMemory.profile` — global слот, персистится в `AppPaths.longTermFile`, грузится в `ContextAwareAgent.ensureLoaded()`.
- `PromptBuilder` уже рендерит `UserProfile` в system prompt → **профиль уже подмешивается в каждый запрос**.
- `/memory` не умеет редактировать профиль (только `show long`).
- `StickyFactsStrategy.updateFacts` — готовый паттерн LLM-извлечения для переиспользования.

## Принятые решения
1. Наполнение: ручная `/profile` + LLM авто-извлечение (`ProfileExtractor`, opt-in `--auto-profile`).
2. Поля: добавить `about: String?` в `UserProfile` (лекция: стиль / constraints / контекст).
3. Авто-извлечение opt-in (флаг `--auto-profile`, по умолчанию выкл); on-demand `/profile extract` доступен всегда.
4. Профиль — global (один пользователь), в `LongTermMemory.profile`.

## Декомпозиция (выполнять последовательно)

| # | Файл | Задача | Зависимости |
|---|---|---|---|
| 01 | `01-userprofile-about.md` | Добавить `about` в `UserProfile` + `isEmpty()` | — |
| 02 | `02-promptbuilder-about.md` | Рендер `About:` в `UserProfile.renderBlock()` | 01 |
| 03 | `03-profile-extractor.md` | `ProfileExtractor` (LLM извлечение + `mergeProfile`) | 01 |
| 04 | `04-agent-integration.md` | `ContextAwareAgent`: аксессоры `getProfile/setProfile`, `--auto-profile` hook (turnCount % N) | 03 |
| 05 | `05-cli-profile-command.md` | `/profile` команда + флаг `--auto-profile` + help | 04 |
| 06 | `06-tests.md` | Тесты: PromptBuilder about, ProfileExtractor, agent profile+auto, schema evolution | 01–05 |
| 07 | `07-verification.md` | Manual REPL (разные профили, extract, --auto-profile, рестарт) + build | 06 |

## Точки расширения
- Day 13 (task state machine): `WorkingMemory.taskState` + `state/` + `StatefulAgent`. Профиль уже подключён, не трогается.
- `mergeProfile` эвристичен (constraints аккумулируются) — уточнение Day 13+.

## Риски
- Авто-извлечение — доп. LLM-вызов каждые N ходов. Opt-in; on-demand не добавляет регулярных вызовов.
- `/profile remove constraint` — точное совпадение, fallback на contains.
