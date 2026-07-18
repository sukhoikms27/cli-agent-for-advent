#!/usr/bin/env bash
#
# update-rag-index.sh — пересобрать RAG-индекс локально и загрузить на VPS.
#
# День 32: pred-built RAG-индекс хранится на VPS и скачивается CI перед AI-review.
# Этот скрипт автоматизирует обновление индекса при изменении AGENTS.md/README/исходников:
#   1. Локальная индексация через cli-agent (Ollama nomic-embed-text, ~10 сек).
#   2. Загрузка index.json на VPS в /opt/cli-agent-rag/ (atomic swap).
#   3. Health-check: GET /rag/index.json через Caddy — подтверждение, что CI скачает новый индекс.
#
# Запуск НА машине-разработчика (где есть локальная Ollama + cli-agent):
#   bash scripts/rag/update-rag-index.sh
#
# Опции (через env или флаги):
#   --dry-run       показать, что будет сделано, без реальных действий
#   --no-upload     только локальная индексация, без загрузки на VPS
#   --vps-host      VPS host (default: 77.91.94.114, из $RAG_VPS_HOST)
#   --vps-user      VPS user (default: root, из $RAG_VPS_USER)
#   --vps-pass      VPS password (default: из $RAG_VPS_PASS; если нет — спросит)
#   --bearer        bearer-токен для health-check (default: из $RAG_BEARER)
#
# Пример:
#   RAG_VPS_PASS='secret' bash scripts/rag/update-rag-index.sh
#   bash scripts/rag/update-rag-index.sh --dry-run
#
set -euo pipefail

# ── конфигурация (defaults) ────────────────────────────────────────────────────
VPS_HOST="${RAG_VPS_HOST:-77.91.94.114}"
VPS_USER="${RAG_VPS_USER:-root}"
VPS_PASS="${RAG_VPS_PASS:-}"
BEARER="${RAG_BEARER:-}"
VPS_RAG_DIR="/opt/cli-agent-rag"
VPS_RAG_FILE="${VPS_RAG_DIR}/index.json"
CADDY_PORT=11435
LOCAL_INDEX="${XDG_DATA_HOME:-$HOME/.local/share}/cli-agent/rag/index.json"

DRY_RUN=false
NO_UPLOAD=false

# ── парсинг аргументов ────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)     DRY_RUN=true; shift ;;
        --no-upload)   NO_UPLOAD=true; shift ;;
        --vps-host)    VPS_HOST="$2"; shift 2 ;;
        --vps-user)    VPS_USER="$2"; shift 2 ;;
        --vps-pass)    VPS_PASS="$2"; shift 2 ;;
        --bearer)      BEARER="$2"; shift 2 ;;
        -h|--help)
            grep '^#' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "⚠️ Неизвестный аргумент: $1 (используйте --help)" >&2
            exit 64   # POSIX EX_USAGE
            ;;
    esac
done

# ── helpers ───────────────────────────────────────────────────────────────────
log()  { echo "▸ $*"; }
ok()   { echo "✓ $*"; }
warn() { echo "⚠️  $*" >&2; }
die()  { echo "❌ $*" >&2; exit 1; }

# Запрос пароля интерактивно, если не задан (и нужен upload).
ask_password_if_needed() {
    if [[ "$NO_UPLOAD" == "true" || "$DRY_RUN" == "true" ]]; then
        return 0
    fi
    if [[ -z "$VPS_PASS" ]]; then
        log "Пароль VPS не задан (RAG_VPS_PASS/--vps-pass). Введите вручную:"
        read -s -p "  Password for ${VPS_USER}@${VPS_HOST}: " VPS_PASS
        echo
        [[ -z "$VPS_PASS" ]] && die "Пустой пароль — отмена."
    fi
}

# Проверка sshpass (нужен для non-interactive SSH по паролю).
require_sshpass() {
    if [[ "$NO_UPLOAD" == "true" || "$DRY_RUN" == "true" ]]; then
        return 0
    fi
    if ! command -v sshpass >/dev/null 2>&1; then
        die "sshpass не установлен. Установка: brew install sshpass (macOS) / apt install sshpass (Linux)."
    fi
}

# SSH-обёртка с sshpass (если пароль задан) или обычный ssh (для key-based auth).
ssh_cmd() {
    if [[ -n "$VPS_PASS" ]]; then
        sshpass -p "$VPS_PASS" ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 "${VPS_USER}@${VPS_HOST}" "$@"
    else
        ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 "${VPS_USER}@${VPS_HOST}" "$@"
    fi
}

scp_cmd() {
    if [[ -n "$VPS_PASS" ]]; then
        sshpass -p "$VPS_PASS" scp -o StrictHostKeyChecking=no "$@"
    else
        scp -o StrictHostKeyChecking=no "$@"
    fi
}

# ── pre-flight checks ────────────────────────────────────────────────────────
log "Pre-flight checks..."

# 1. Локальная Ollama живая и nomic-embed-text установлен.
if [[ "$DRY_RUN" == "false" ]]; then
    if ! curl -s --max-time 3 http://localhost:11434/api/tags >/dev/null 2>&1; then
        die "Локальная Ollama недоступна на localhost:11434. Запустите: ollama serve"
    fi
    if ! curl -s http://localhost:11434/api/tags 2>/dev/null | grep -q "nomic-embed-text"; then
        die "nomic-embed-text не установлен. Запустите: ollama pull nomic-embed-text"
    fi
    ok "Локальная Ollama + nomic-embed-text готовы"
fi

# 2. cli-agent собран (installDist). Если нет — соберём.
CLI_BIN="./build/install/cli-agent/bin/cli-agent"
if [[ ! -x "$CLI_BIN" ]]; then
    if [[ "$DRY_RUN" == "true" ]]; then
        warn "[dry-run] cli-agent не собран — нужен ./gradlew installDist"
    else
        log "cli-agent не собран, собираю (./gradlew installDist)…"
        ./gradlew installDist -q || die "Сборка не удалась"
        ok "cli-agent собран"
    fi
else
    ok "cli-agent уже собран"
fi

# 3. sshpass для upload.
require_sshpass
ask_password_if_needed

# ── Шаг 1: локальная индексация ───────────────────────────────────────────────
echo ""
log "Шаг 1/3: локальная индексация RAG (Ollama nomic-embed-text, ~10 сек)"
if [[ "$DRY_RUN" == "true" ]]; then
    warn "[dry-run] пропускаю индексацию"
else
    # Запуск /rag index через non-interactive pipe (clikt читает из stdin).
    # Используем chat + /rag index, как в REPL.
    echo "/rag index" | "$CLI_BIN" chat 2>&1 | grep -E "Loaded|Indexed|Embedded|chunks|Error" | head -5
    if [[ ! -f "$LOCAL_INDEX" ]]; then
        die "Индекс не создан: $LOCAL_INDEX"
    fi
    CHUNKS=$(python3 -c "import json; d=json.load(open('$LOCAL_INDEX')); print(len(d.get('chunks',[])))" 2>/dev/null || echo "?")
    SIZE=$(wc -c < "$LOCAL_INDEX" | tr -d ' ')
    ok "Индекс готов: ${CHUNKS} чанков, $((SIZE / 1024 / 1024)) MB → $LOCAL_INDEX"
fi

# Дальше — только если upload нужен.
if [[ "$NO_UPLOAD" == "true" ]]; then
    echo ""
    ok "Готово (--no-upload). Индекс: $LOCAL_INDEX"
    exit 0
fi

# ── Шаг 2: загрузка на VPS (atomic) ──────────────────────────────────────────
echo ""
log "Шаг 2/3: загрузка на VPS ${VPS_HOST}:${VPS_RAG_FILE}"
if [[ "$DRY_RUN" == "true" ]]; then
    warn "[dry-run] пропускаю upload"
else
    # mkdir + загрузка во временный файл + atomic rename (чтобы CI не скачал половинчатый файл).
    ssh_cmd "mkdir -p ${VPS_RAG_DIR}" || die "Не удалось создать каталог на VPS"
    log "Загружаю index.json во временный файл…"
    scp_cmd "$LOCAL_INDEX" "${VPS_USER}@${VPS_HOST}:${VPS_RAG_FILE}.tmp" \
        || die "Upload не удалась (проверьте пароль/сеть)"
    log "Atomic swap: ${VPS_RAG_FILE}.tmp → ${VPS_RAG_FILE}"
    ssh_cmd "mv ${VPS_RAG_FILE}.tmp ${VPS_RAG_FILE}" \
        || die "Swap не удался (временный файл остался: ${VPS_RAG_FILE}.tmp)"
    ok "Индекс загружен на VPS"
fi

# ── Шаг 3: health-check ───────────────────────────────────────────────────────
echo ""
log "Шаг 3/3: health-check (CI-симуляция: GET /rag/index.json)"
if [[ "$DRY_RUN" == "true" ]]; then
    warn "[dry-run] пропускаю health-check"
else
    if [[ -z "$BEARER" ]]; then
        warn "BEARER не задан (RAG_BEARER/--bearer) — health-check пропущен."
        warn "CI использует секрет CLI_AGENT_RAG_EMBEDDING_TOKEN для этого."
    else
        log "Проверяю endpoint http://${VPS_HOST}:${CADDY_PORT}/rag/index.json…"
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            -H "Authorization: Bearer ${BEARER}" \
            "http://${VPS_HOST}:${CADDY_PORT}/rag/index.json")
        if [[ "$HTTP_CODE" == "200" ]]; then
            ok "Health-check пройден: HTTP 200 (CI сможет скачать индекс)"
        else
            die "Health-check failed: HTTP $HTTP_CODE (проверьте Caddy: systemctl status caddy на VPS)"
        fi
    fi
fi

# ── итог ──────────────────────────────────────────────────────────────────────
echo ""
if [[ "$DRY_RUN" == "true" ]]; then
    ok "Dry-run завершён — ничего не менялось."
else
    ok "Готово. RAG-индекс обновлён."
    echo "  Локально: $LOCAL_INDEX"
    echo "  VPS:      ${VPS_USER}@${VPS_HOST}:${VPS_RAG_FILE}"
    echo ""
    echo "  Следующий CI-запуск AI-review подхватит свежий индекс."
fi
