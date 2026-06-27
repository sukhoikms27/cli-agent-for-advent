# Задача 13. Демо-сценарий дня 18 (периодическая работа локально)

## Контекст

Демонстрация полного цикла Day 18: MCP-сервер 24/7 копит погоду по расписанию → агент через MCP-tools
отдаёт текущую погоду и **агрегированную сводку** за период. Демо проходит **локально** в http-режиме
с интервалом 60с (без обязательного VPS-деплоя для зачёта — см. подсказку куратора в `00-task.md`).

> Сценарии A, B — без LLM (raw JSON-RPC, чистая серверная логика). C, D, E — через REPL агента,
> требуют `CLI_AGENT_API_KEY` (LLM сама выбирает/чейнит tools, Day 17 wiring).

## Подготовка

```bash
export XDG_DATA_HOME=/tmp/cliagent-day18-demo
rm -rf $XDG_DATA_HOME
./gradlew :mcp-server:test build shadowJar
export CLI_AGENT_GITHUB_TOKEN=<pat>     # для get_repo (регрессионная проверка)
export CLI_AGENT_API_KEY=<zai-key>      # для LLM-сценариев C/D/E
```

## Что проверяем (маппинг на задание курса)

| # | Требование | Сценарий |
|---|---|---|
| 1 | «сохранять данные» | A2 (moscow.json растёт) |
| 2 | «периодический сбор» | A1/A2 (scheduler every 60s) |
| 3 | «выполняться по расписанию» | A1 (scheduler) + D (`collect_weather` on-demand) |
| 4 | «агрегированный результат» | E (`get_weather_summary` через LLM) |
| 5 | «24/7 агент со сводкой» | весь сценарий (http-сервер + agent REPL) |
| 6 | 0 регрессий Day 17 | B (`get_repo`) |

---

## Сценарий A. Scheduler копит данные (БЕЗ LLM)

**A1. Запуск сервера http, интервал 60с, город Москва:**
```bash
CLI_AGENT_MCP_MODE=http CLI_AGENT_MCP_PORT=8080 CLI_AGENT_MCP_TOKEN=dev-token \
CLI_AGENT_WEATHER_INTERVAL_SECONDS=60 CLI_AGENT_WEATHER_CITIES=Moscow \
java -jar mcp-server/build/libs/mcp-server-0.1.0-all.jar &
```
**Ожидание (stderr):**
```
[weather] scheduler started: cities=[Moscow] interval=60s
[weather] collected Moscow: 18°C, Преимущественно ясно / переменная облачность
```
Затем каждые 60с — новый `[weather] collected Moscow: ...`.

**A2. Накопление в JSON (подождать ~3 мин = 3-4 снапшота):**
```bash
cat $XDG_DATA_HOME/cli-agent/weather/moscow.json
```
**Ожидание:** `{"snapshots":[{...},{...},{...},...]}` — массив растёт, каждый снапшот с
`timestamp`/`temperature_celsius`/`weather_code`. Один файл на город (slug `moscow`).

**A3. Несколько городов:**
```bash
# рестарт с двумя городами
kill %1
CLI_AGENT_WEATHER_CITIES="Moscow,Saint Petersburg" ... java -jar ... &
```
**Ожидание:** два файла `moscow.json` + `saint-petersburg.json`, независимо наполняются.

---

## Сценарий B. get_repo не сломан (регрессия Day 17, БЕЗ LLM)

Через raw JSON-RPC на тот же сервер (bearer):
```bash
printf '%s\n%s\n%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"s","version":"0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_repo","arguments":{"owner":"JetBrains","repo":"kotlin"}}}' \
  | CLI_AGENT_MCP_TOKEN=dev-token ... # (через http-клиент MCP, либо тот же stdio-бин)
```
**Ожидание:** `isError:false`, сводка со звёздами/языком/описанием JetBrains/kotlin — **идентично Day 17**.

---

## Сценарий C. tools/list = 4 tools (БЕЗ LLM)

```bash
# raw tools/list
```
**Ожидание:** 4 tool'а — `get_repo`, `collect_weather`, `get_current_weather`,
`get_weather_summary` (последние три с schema `city` [+`hours` для summary]).

---

## Сценарий D. Агент → collect_weather + get_current_weather (ТРЕБУЕТ LLM)

В отдельном терминале — запустить клиента-агента против локального сервера:
```bash
CLI_AGENT_MCP_URL=http://127.0.0.1:8080/mcp CLI_AGENT_MCP_TOKEN=dev-token \
CLI_AGENT_API_KEY=$CLI_AGENT_API_KEY \
./gradlew run --args="chat"
```

**D1. Текущая погода:**
```text
cli-agent> Какая сейчас погода в Москве?
```
**Ожидание:** LLM вызывает `get_current_weather(Moscow)` → отвечает температурой/ветром/условиями.
(Если данных в store ещё нет — tool делает свежий fetch и append, Day 18 задача 04 fallback.)

**D2. On-demand сбор (по подсказке куратора — «по расписанию через tool»):**
```text
cli-agent> Собери свежие данные о погоде в Казани и сохрани
```
**Ожидание:** LLM вызывает `collect_weather("Kazan")` → `kazan.json` создаётся/растёт на сервере,
агент подтверждает сохранение.

---

## Сценарий E. Агент → get_weather_summary (агрегат, ТРЕБУЕТ LLM)

Убедиться, что в `moscow.json` накопилось ≥2-3 снапшотов (сценарий A, ~3 мин работы scheduler'а).

```text
cli-agent> Дай сводку погоды в Москве за последний час
```
**Ожидание:** LLM вызывает `get_weather_summary(Moscow, hours=1)` → получает агрегат:
```
Сводка погоды: Moscow (последние 1 ч, замеров: 3)
Период: 26.06 14:00 … 26.06 14:30
Температура: средняя 18°C, мин 17°C, макс 19°C
Осадки суммарно: 0.0 мм
Преобладающие условия: Преимущественно ясно / переменная облачность
```
Агент переформулирует сводку для пользователя. **Это и есть «агрегированный результат» / «регулярный summary».**

```text
cli-agent> Сравни погоду в Москве и Казани за последние сутки
```
**Ожидание:** LLM делает **несколько tool-call'ов** в одном ходе (`get_weather_summary` ×2) —
chaining через `runToolLoop` (`MAX_TOOL_ROUNDS=4`, Day 17), затем сравнивает оба агрегата в ответе.
Демонстрирует композицию нескольких tools (мостик к Day 19).

---

## Граничные/негативные кейсы

| # | Действие | Ожидание |
|---|---|---|
| E1 | `get_weather_summary` для города без данных | `isError:true` «Нет накопленных данных... Вызовите collect_weather» |
| E2 | `collect_weather` с невалидным именем (`../etc`) | `isError:true` (CITY_REGEX отсекает) или `null`→ tool-error |
| E3 | scheduler с `CLI_AGENT_WEATHER_CITIES=` (пусто) | `[weather] WARN: scheduler disabled: no cities`, 0 сборов |
| E4 | scheduler с `CLI_AGENT_WEATHER_INTERVAL_SECONDS=0` | disabled (interval≤0), WARN |
| E5 | сервер без `CLI_AGENT_GITHUB_TOKEN` | `get_repo` → `isError:true` (как Day 17), погодные tools работают |
| E6 | http-запрос без/с неверным bearer | 401 (Day 17-vps auth, не тронут) |

## Автоматизированные тесты (ссылка)

| Тест-класс | Что покрывает | Сценарий демо |
|---|---|---|
| `WeatherStoreTest` (08) | persist/atomic/slug/schema | A2 |
| `WeatherClientTest` (09) | geocode/forecast/collect, injection | A1, D, E2 |
| `WeatherSchedulerTest` (10) | циклы/изоляция/cancellation | A1, E3, E4 |
| `WeatherAggregateTest` (11) | aggregate avg/min/max/sum | E |

## Чек-лист приёмки (соответствие заданию + доптребованиям)

- [x] «сохранять данные» — A2 (moscow.json, atomic write — тест 08)
- [x] «периодический сбор данных» — A1 (scheduler 60s, тест 10) + D (`collect_weather`)
- [x] «выполняться по расписанию» — A1 (auto scheduler) + D (on-demand tool, по куратору)
- [x] «агрегированный результат» — E (`get_weather_summary` avg/min/max/sum, тест 11)
- [x] «24/7 агент со сводкой» — http-сервер + REPL (весь сценарий; VPS-деплой Day 17-vps совместим)
- [x] 0 регрессий Day 17 — B (`get_repo` идентичен, тесты корня зелёные)
- [x] «по расписанию через tool» — D (подсказка куратора реализована)

## Зависимости (задачи)

12 (верификация). Завершает Day 18: демо показывает периодическую работу локально + агент через LLM.
