# Задача 15 (доработка Day 13). Верификация

## Цель
Подтвердить сборку, тесты и end-to-end: stage-поведение + детерминированные gated-переходы + escape.

## Автоматизация
```bash
./gradlew test          # все зелёные, включая Day 11/12/13
./gradlew build
./gradlew installDist
```

## Manual REPL
```text
cli-agent chat
cli-agent> /task start экран профиля, programmatic View-based, MVI, тесты
cli-agent> /task set clarify
cli-agent> что мне нужно учесть?          → агент УТОЧНЯЕТ (вопросы), не пишет код (stage-prompt clarify)
cli-agent> /task next                     → clarify→planning (canAdvance=true)
cli-agent> /task next                     → warn: planning not ready, /task plan <text>  (HARD BLOCK)
cli-agent> /task plan 1) View 2) Reducer 3) ViewModel 4) тесты
cli-agent> /task next                     → planning→execution (approvedPlan set)
cli-agent> /task next                     → warn: execution not ready, /task impl <text>  (HARD BLOCK)
cli-agent> /task impl Reducer+ViewModel готовы
cli-agent> /task next                     → execution→validation
cli-agent> /task next                     → warn: validation not ready, /task verdict <text>
cli-agent> /task verdict все тесты зелёные, нет XML/Compose
cli-agent> /task next                     → validation→done
cli-agent> /task show                     → все артефакты видны (plan/impl/verdict)
```

## Доп. проверки
- `/task set execution` из planning → force escape (минуя gate).
- Пауза/resume: `/exit` → `cli-agent chat -c <chatId>` → stage-prompt восстанавливается, `/task show`
  показывает артефакты.
- `taskState == null` (без `/task start`) → поведение = Day 13 (инвариант совместимости).

## Критерии готовности (соответствие Варианту 2 автора курса)
- ✅ Stage-enforcing промпты (`StagePromptTemplates`, поведение per stage).
- ✅ Детерминированные artifact-gated переходы (`canAdvance`, hard block).
- ✅ Артефакты стадий (`approvedPlan`/`implementation`/`verdict`), рендер, персистентность.
- ✅ Escape hatch (`/task set` force).
- ✅ Инвариант совместимости (без активной задачи поведение = Day 13; профиль Day 12 не затронут).

## Зависимости
Задача 14.
