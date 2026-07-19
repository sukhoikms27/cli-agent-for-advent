# Скрипты RAG-инфраструктуры

Поддержка pred-built RAG-индекса на VPS для CI AI-review (день 32). Индекс хранится на VPS и
скачивается GitHub Action перед ревью — это обходит медленную CPU-индексацию в CI (~22 мин).

## Архитектура

```
[Локально, при изменении AGENTS.md/README]      [VPS: 77.91.94.114]
update-rag-index.sh                              /opt/cli-agent-rag/index.json
   ├── /rag index (локальная Ollama, ~10 сек)        ↑
   └── scp → VPS (atomic swap) ──────────────────────┘

[GitHub Action на PR]
1. GET /rag/index.json с VPS (~0.4 сек, 13MB)
2. cli-agent review-pr: query embedding через VPS /api/embed (~22 сек)
3. retrieval по pred-built индексу → LLM ревью → PR-комментарий
```

## Скрипты

| Скрипт | Назначение |
|---|---|
| `update-rag-index.sh` | Перестроить RAG-индекс локально (Ollama) + загрузить на VPS + health-check |

## update-rag-index.sh

**Когда запускать:** после значимых изменений в `AGENTS.md`, `README.md`, `src/main/kotlin/` —
всего, что индексируется RAG. Без этого CI будет ревьюить по устаревшему индексу.

**Требования (на машине разработчика):**
- Локальная Ollama + `nomic-embed-text` (`ollama pull nomic-embed-text`)
- `cli-agent` собран (`./gradlew installDist`) — скрипт соберёт сам, если нет
- `sshpass` для SSH по паролю (`brew install sshpass` / `apt install sshpass`), либо SSH key

**Запуск:**
```bash
# С паролем через env (самый простой способ):
RAG_VPS_PASS='your_password' bash scripts/rag/update-rag-index.sh

# С health-check (нужен bearer-токен Caddy, тот же что в GitHub Secrets):
RAG_VPS_PASS='your_password' RAG_BEARER='caddy_bearer_token' \
    bash scripts/rag/update-rag-index.sh

# Dry-run (показать план, ничего не делать):
bash scripts/rag/update-rag-index.sh --dry-run

# Только локальная индексация без upload:
bash scripts/rag/update-rag-index.sh --no-upload
```

**Опции (env или флаги):**

| Env | Флаг | Default | Описание |
|---|---|---|---|
| `RAG_VPS_HOST` | `--vps-host` | `77.91.94.114` | VPS host |
| `RAG_VPS_USER` | `--vps-user` | `root` | VPS user |
| `RAG_VPS_PASS` | `--vps-pass` | (спросит) | VPS password (для sshpass) |
| `RAG_BEARER`   | `--bearer`   | (skip health-check) | Caddy bearer для проверки endpoint |
| — | `--dry-run` | false | Показать план, ничего не менять |
| — | `--no-upload` | false | Только локальная индексация |

## Что делает скрипт

1. **Pre-flight:** проверяет локальную Ollama + `nomic-embed-text`, собирает `cli-agent` если нужно.
2. **Локальная индексация:** `/rag index` через cli-agent (~10 сек, 496 чанков, ~13 MB).
3. **Upload на VPS:** `scp` во временный файл + `mv` (atomic swap) — CI не скачает половинчатый файл.
4. **Health-check:** `GET /rag/index.json` через Caddy с bearer — подтверждает, что CI сможет скачать.

## VPS infra (настроено один раз)

- **Caddy reverse-proxy** на порту `11435` с bearer-auth:
  - `GET /rag/index.json` — pred-built индекс (13MB, atomic)
  - `POST /api/embed` — query embedding через Ollama `nomic-embed-text`
  - `/api/chat` **запрещён** (LLM не открывается наружу, path-restrict в Caddy)
- **Caddyfile:** `/etc/caddy/Caddyfile` на VPS
- **Индекс:** `/opt/cli-agent-rag/index.json`

## CI Secrets (GitHub → Settings → Secrets)

| Секрет | Назначение |
|---|---|
| `CLI_AGENT_API_KEY` | z.ai (или OpenAI-compatible) API key для LLM-генерации ревью |
| `CLI_AGENT_RAG_EMBEDDING_TOKEN` | Bearer для VPS endpoints (index download + query embedding) |

## Troubleshooting

**«Локальная Ollama недоступна на localhost:11434»** — запустите `ollama serve`.

**«nomic-embed-text не установлен»** — `ollama pull nomic-embed-text` (~270 MB).

**«sshpass не установлен»** — macOS: `brew install sshpass`; Linux: `apt install sshpass`.
Альтернатива: настройте SSH key и не передавайте `RAG_VPS_PASS`.

**«Health-check failed: HTTP 403»** — неверный `RAG_BEARER` или Caddy не запущен.
Проверьте: `ssh root@vps 'systemctl status caddy && cat /etc/caddy/Caddyfile'`.

**«Health-check failed: HTTP 404»** — индекс не загрузился или Caddy path mismatch.
Проверьте: `ssh root@vps 'ls -la /opt/cli-agent-rag/'`.

**CI скачивает устаревший индекс** — пере запустите `update-rag-index.sh` после изменений в AGENTS.md.
Скрипт загружает новый индекс atomic'но — следующий CI-запуск подхватит свежую версию.
