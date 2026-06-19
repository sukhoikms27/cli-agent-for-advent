# Задача 10. Верификация

## Цель
Подтвердить сборку, тесты и end-to-end сценарий «пауза + resume без повторных объяснений».

## Автоматизация
```bash
./gradlew test          # все зелёные, включая Day 11/12
./gradlew build
./gradlew installDist   # для интерактива/JLine надёжнее gradlew run
```

## Manual REPL (требование курса: пауза + resume)
```text
cli-agent chat
cli-agent> /task start реализовать стейт-машину задачи
cli-agent> /task show                     → Stage: planning, Current step: ...
cli-agent> /task next                     → planning → execution
cli-agent> /task set validation           → execution→validation (разрешён)
cli-agent> /task expect тесты проходят
cli-agent> /exit                          ← ПАУЗА
```
```bash
cli-agent chat -c <chatId>                 ← RESUME
```
```text
cli-agent> /task show                     → состояние на месте (validation, expected action)
cli-agent> что осталось сделать?          → агент отвечает с учётом состояния, БЕЗ повторного объяснения
cli-agent> /task done                     → validation → done
```

## Доп. проверки
- `/task next` из DONE → warn (нет следующей).
- `/task set execution` из PLANNING → warn о незаконном переходе, но стадия `execution` (force).
- `/task back` откатывает стадию по history.
- `/reset` → `/task show` = `No active task`.
- Перезапуск `cli-agent chat -c <chatId>` — `/task show` показывает сохранённое состояние.

## Критерии готовности (соответствие заданию)
- ✅ Состояние задачи как конечный автомат (`TaskStateMachine`, `TaskStage`).
- ✅ Этап / текущий шаг / ожидаемое действие (`TaskState`).
- ✅ Переходы `clarify → planning → execution → validation → done`.
- ✅ Пауза на любом этапе (выход из REPL, состояние персистится).
- ✅ Продолжение без повторных объяснений (resume + инъекция в prompt).
- ✅ Профиль Day 12 не затронут; без новых флагов поведение = Day 12.

## Зависимости
Задача 09.
