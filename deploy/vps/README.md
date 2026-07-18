# День 30 — Приватный LLM-сервис на VPS

Деплой-артефакты для приватного LLM-сервиса: локальная модель (Ollama, qwen2.5:7b Q5) на CPU-VPS
за reverse proxy (Caddy, auto-HTTPS + Bearer auth). CLI-агент подключается через env-переменные.

> **Подробный план** (VPS provisioning, SSH-hardening, troubleshooting, future work):
> [`../../.artifacts/week-06/day-30-draft.md`](../../.artifacts/week-06/day-30-draft.md) (796 строк)

## Файлы

| Файл | Назначение |
|---|---|
| `deploy.sh` |一键-деплой на VPS: Ollama install + VPS-модель + Caddy + firewall |
| `Modelfile.qwen25-7b` | Ollama Modelfile: qwen2.5:7b с `num_ctx 8192`, `num_predict 2048`, `keep_alive 30m` |
| `Caddyfile` | Reverse proxy: auto-HTTPS (Let's Encrypt) + Bearer auth + логирование |
| `ollama.service.override.conf` | systemd: `OLLAMA_NUM_PARALLEL=1`, bind 127.0.0.1, MemoryMax 6G |

## Быстрый старт (на VPS)

```bash
# 1. SSH-hardening + firewall (см. day-30-draft.md Этап 0.3-0.4)

# 2. Запуск deploy script
./deploy/vps/deploy.sh --domain llm.ВАШ-ДОМЕН.ru --token $(openssl rand -base64 32)

# Скрипт:
#   - ставит Ollama, настраивает systemd override (num_parallel=1, bind localhost)
#   - pull qwen2.5:7b-instruct-q5_K_M (~5 GB), создаёт qwen2.5-7b-vps (num_ctx 8192)
#   - ставит Caddy, настраивает reverse proxy + Bearer auth + auto-HTTPS
#   - firewall: 22/80/443 open, 11434 НЕ торчит наружу
```

## Подключение CLI-агента

```bash
# Вариант 1: явный provider=ollama + токен (Bearer шлётся при непустом apiKey)
CLI_AGENT_PROVIDER=ollama \
CLI_AGENT_BASE_URL=https://llm.ВАШ-ДОМЕН.ru/v1 \
CLI_AGENT_MODEL=qwen2.5-7b-vps \
CLI_AGENT_API_KEY=ВАШ_ТОКЕН \
./gradlew run --args="chat"

# Вариант 2: openai-compatible (requiresApiKey=true, явный apiKey)
CLI_AGENT_PROVIDER=openai-compatible \
CLI_AGENT_BASE_URL=https://llm.ВАШ-ДОМЕН.ru/v1 \
CLI_AGENT_MODEL=qwen2.5-7b-vps \
CLI_AGENT_API_KEY=ВАШ_ТОКЕН \
./gradlew run --args="chat"
```

Или через REPL после старта:
```
/local on qwen2.5-7b-vps    # live-switch (день 26)
```
(предварительно `/config set baseUrl https://llm.ВАШ-ДОМЕН.ru/v1` + `/config set apiKey ВАШ_ТОКЕН`)

## Проверка

```bash
# VPS-side smoke (localhost, без auth)
ollama run qwen2.5-7b-vps "Say hello"

# Remote check (через Caddy, с auth)
curl https://llm.ВАШ-ДОМЕН.ru/v1/models -H "Authorization: Bearer ВАШ_ТОКЕН"

# CLI-агент smoke
/local smoke    # 3 запроса разной сложности (день 26)
```

## Архитектура

```
CLI-агент (laptop)
    │ HTTPS + Bearer token
    ▼
Caddy (VPS, :443) ── auto-HTTPS (Let's Encrypt) + auth
    │
    ▼ 127.0.0.1:11434
Ollama (VPS) ── qwen2.5-7b-vps (Q5_K_M, num_ctx 8192, num_parallel=1)
```

- **Ollama** bind 127.0.0.1 — НЕ торчит наружу напрямую.
- **Caddy** терминирует TLS, проверяет Bearer, проксирует на Ollama.
- **systemd** ограничивает ресурсы (MemoryMax 6G, 1 параллельный слот).
- Бюджет: Hetzner CX32 (~€6.5/мес, 4 vCPU/8 GB) — single-user чат, 10-15 tok/s на CPU.

## Что уже готово в коде (дни 25-29)

CLI-агент не требует правок для VPS-подключения — multi-provider dispatch (день 25):
- `LlmProvider.OLLAMA` / `OPENAI_COMPATIBLE` + `LlmClientFactory.create()`.
- `OpenAiCompatibleClient` шлёт `Authorization: Bearer` при непустом apiKey.
- `ModelLimitsRegistry` знает qwen2.5 → 128K/8K (для VPS-модели с num_ctx 8192 — точнее через
  день 29 фикс, но VPS-модель `qwen2.5-7b-vps` prefix-match → qwen2.5 → 128K; num_ctx в Modelfile
  ограничивает реальный контекст на уровне движка).
- `/local on|off|status|smoke` (день 26) + маркировка ответов (день 27).
