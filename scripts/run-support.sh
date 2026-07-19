#!/usr/bin/env bash
#
# run-support.sh — one-shot запуск AI Support Agent (день 33) со всеми зависимостями.
#
# Что делает (по порядку):
#   1. Проверяет Ollama и наличие embedder-модели (nomic-embed-text — нужна всегда для RAG).
#   2. Если provider=zai (default): проверяет CLI_AGENT_API_KEY.
#      Если provider=ollama: дополнительно проверяет LLM-модель.
#   3. Разворачивает демо-данные: 20 тикетов + 11 FAQ-файлов (~65 тем про мессенджер MAX)
#      через `:support-app:run --args="seed --reset"`.
#   4. Индексирует FAQ в support-specific RAG-индекс (если устарел/отсутствует, или --reindex).
#   5. Запускает Ktor-сервер на http://localhost:${SUPPORT_PORT:-8081}.
#
# После старта откройте URL в браузере — там чат + поля Ticket ID / Email для контекста.
#
# **LLM-провайдер** (день 33 fix): по умолчанию cloud z.ai GLM-5.1 (быстрый, для real-time support-чата).
# Локальная Ollama — только для офлайн/VPS-демо через --provider ollama.
# Embedder всегда Ollama (z.ai не предоставляет embeddings endpoint).
#
# Опции:
#   --provider <name>  zai (default) | ollama
#   --model <name>     LLM модель (zai: glm-5.1, ollama: qwen3:14b)
#   --embed-model <name>   модель эмбеддингов (default: nomic-embed-text)
#   --ollama-url <url>     URL Ollama (default: http://127.0.0.1:11434)
#   --port <N>         переопределить SUPPORT_PORT (default: 8081)
#   --no-seed          пропустить seed-шаг (использовать существующие данные)
#   --no-index         пропустить авто-индексацию RAG (использовать существующий индекс)
#   --reindex          принудительно перестроить RAG-индекс перед стартом
#   --no-server        только seed + проверки, без запуска сервера
#   --kill-existing    (default) убить процесс на порту перед стартом (анти-зомби)
#   --no-kill-existing не убивать существующий процесс — упасть с понятной ошибкой
#   --dry-run          показать проверки и запланированные шаги, без реальных действий
#   --yes              отвечать "yes" на все промпты (для CI/non-interactive)
#   -h|--help          показать эту справку
#
# Примеры:
#   bash scripts/run-support.sh                          # дефолт: z.ai cloud + локальный embedder
#   bash scripts/run-support.sh --provider ollama        # офлайн: всё на локальной Ollama
#   bash scripts/run-support.sh --dry-run                # проверить готовность без изменений
#   bash scripts/run-support.sh --yes                    # без промптов (CI)
#   bash scripts/run-support.sh --port 8090 --model glm-5.1
#
set -euo pipefail

# ── конфигурация (defaults) ────────────────────────────────────────────────────
PROVIDER="${SUPPORT_PROVIDER:-zai}"
OLLAMA_URL="${OLLAMA_BASE_URL:-http://127.0.0.1:11434}"
EMBED_MODEL="${SUPPORT_RAG_EMBEDDING_MODEL:-nomic-embed-text}"
PORT="${SUPPORT_PORT:-8081}"
# LLM_MODEL пустой → default берётся из config.json или provider-default в Kotlin.
LLM_MODEL=""

DRY_RUN=false
ASSUME_YES=false
NO_SEED=false
NO_INDEX=false
REINDEX=false
NO_SERVER=false
# По умолчанию автоматическая очистка порта ВКЛЮЧЕНА — защищает пользователя от зомби-процессов
# (Gradle JavaExec оставляет child-JVM после Ctrl+C, это известная проблема gradle#1185).
# --no-kill-existing — отключить (тогда при занятом порту скрипт просто упадёт с ошибкой).
KILL_EXISTING=true

# ── парсинг аргументов ────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --provider)       PROVIDER="$2"; shift 2 ;;
        --model)          LLM_MODEL="$2"; shift 2 ;;
        --embed-model)    EMBED_MODEL="$2"; shift 2 ;;
        --ollama-url)     OLLAMA_URL="$2"; shift 2 ;;
        --port)           PORT="$2"; shift 2 ;;
        --no-seed)          NO_SEED=true; shift ;;
        --no-index)         NO_INDEX=true; shift ;;
        --reindex)          REINDEX=true; shift ;;
        --no-server)        NO_SERVER=true; shift ;;
        --kill-existing)    KILL_EXISTING=true; shift ;;
        --no-kill-existing) KILL_EXISTING=false; shift ;;
        --dry-run)        DRY_RUN=true; shift ;;
        --yes|-y)         ASSUME_YES=true; shift ;;
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

# Нормализация провайдера.
[[ "$PROVIDER" != "zai" && "$PROVIDER" != "ollama" ]] && {
    echo "❌ --provider должен быть 'zai' или 'ollama' (получено: '$PROVIDER')" >&2
    exit 64
}

# Если model не задан явно — подставляем provider-default для отображения в логах
# (в Kotlin всё равно берётся из config.json или этого env override).
if [[ -z "$LLM_MODEL" ]]; then
    case "$PROVIDER" in
        zai)    LLM_MODEL="${SUPPORT_MODEL:-glm-5.1}" ;;
        ollama) LLM_MODEL="${SUPPORT_MODEL:-qwen3:14b}" ;;
    esac
fi

# ── helpers ───────────────────────────────────────────────────────────────────
log()  { echo "▸ $*"; }
ok()   { echo "✓ $*"; }
warn() { echo "⚠️  $*" >&2; }
die()  { echo "❌ $*" >&2; exit 1; }
prefixed() { sed 's/^/  /'; }

# Подтверждение yes/no. Первый аргумент — промпт. Возвращает 0/1 через код выхода.
confirm() {
    local prompt="$1"
    if [[ "$ASSUME_YES" == "true" ]]; then
        log "$prompt — yes (--yes)"
        return 0
    fi
    if [[ "$DRY_RUN" == "true" ]]; then
        log "$prompt — skipped (--dry-run)"
        return 1
    fi
    read -r -p "  $prompt [y/N] " answer
    [[ "$answer" =~ ^[Yy]$ ]]
}

# Проверка модели Ollama по base-name (без тега): nomic-embed-text == nomic-embed-text:latest.
ollama_has_model() {
    local model="$1"
    local base="${model%%:*}"
    curl -sf "${OLLAMA_URL}/api/tags" 2>/dev/null | \
        python3 -c "import sys,json; sys.exit(0 if any('$base' == m['name'].split(':')[0] for m in json.load(sys.stdin).get('models',[])) else 1)" 2>/dev/null
}

check_or_pull_model() {
    local model="$1"
    local kind="$2"
    if ollama_has_model "$model"; then
        ok "$kind: $model — установлена"
        return 0
    fi
    warn "$kind: $model — отсутствует"
    if confirm "  Скачать $kind '$model'? (может занять несколько ГБ)"; then
        log "ollama pull $model..."
        ollama pull "$model" || die "Не удалось скачать $model"
        ok "$kind: $model — установлена"
    else
        die "$kind '$model' нужен для запуска. Скачайте вручную: ollama pull $model"
    fi
}

# ── pre-flight: порт свободен? ────────────────────────────────────────────────
# Защита от «Address already in use»: если на порту висит зомби-процесс (например, прошлый
# запуск :support-app:run убили через Ctrl+C, но Gradle оставил child-JVM) — очистим его.
# Это частая проблема на macOS/Linux с Gradle JavaExec (issue gradle/gradle#1185):
# kill wrapper-процесса не каскадно убивает child-JVM, та остаётся держать порт.
pre_flight_port_check() {
    if [[ "$NO_SERVER" == "true" || "$DRY_RUN" == "true" ]]; then
        return 0
    fi
    local pids
    pids=$(lsof -ti tcp:"$PORT" 2>/dev/null || true)
    if [[ -z "$pids" ]]; then
        return 0
    fi
    warn "Порт $PORT занят процессом(ами): $(echo $pids | tr '\n' ' ')"
    if [[ "$KILL_EXISTING" == "true" ]]; then
        log "Убиваем существующий процесс(ы) на порту $PORT (--kill-existing)..."
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 2
        # Кто не умер после SIGTERM — добиваем SIGKILL.
        local still_alive
        still_alive=$(lsof -ti tcp:"$PORT" 2>/dev/null || true)
        if [[ -n "$still_alive" ]]; then
            warn "SIGTERM не помог, посылаем SIGKILL..."
            echo "$still_alive" | xargs kill -9 2>/dev/null || true
            sleep 1
        fi
        if lsof -ti tcp:"$PORT" > /dev/null 2>&1; then
            die "Не удалось освободить порт $PORT. Убейте процесс вручную: lsof -i:$PORT"
        fi
        ok "Порт $PORT освобождён"
    else
        die "Порт $PORT занят. Освободите его вручную (lsof -i:$PORT; kill <pid>), " \
            "или запустите с --kill-existing для автоматической очистки, " \
            "или с --port <N> для другого порта."
    fi
}

# ── проверка базовых зависимостей ─────────────────────────────────────────────
log "Проверка зависимостей..."

# 1. Java + Gradle wrapper.
if ! command -v java >/dev/null 2>&1; then
    die "Java не найдена. Нужен JDK 21+ (проверьте SDKMAN / brew install openjdk@21)."
fi
JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1 || true)
ok "java найдена (версия ${JAVA_VERSION})"
[[ ! -x ./gradlew ]] && die "./gradlew не найден или не исполняемый. Запускайте из корня проекта."

# 2. Ollama binary + сервер (всегда нужен — embedder работает через Ollama).
if ! command -v ollama >/dev/null 2>&1; then
    die "Ollama не найдена в PATH. Установите: https://ollama.com/download
       macOS:  brew install ollama && brew services start ollama
       Linux:  curl -fsSL https://ollama.com/install.sh | sh && systemctl start ollama"
fi
ok "ollama найден: $(command -v ollama)"

if ! curl -sf "${OLLAMA_URL}/api/tags" >/dev/null 2>&1; then
    die "Ollama-сервер не отвечает на ${OLLAMA_URL}.
       Запустите: ollama serve  (или brew services start ollama / systemctl start ollama)
       Если порт другой — передайте --ollama-url <url>."
fi
ok "Ollama-сервер активен: ${OLLAMA_URL}"

# 3. Embedder — нужен всегда (z.ai не предоставляет embeddings endpoint).
check_or_pull_model "$EMBED_MODEL" "Embedder"

# 4. LLM-проверка зависит от провайдера.
case "$PROVIDER" in
    ollama)
        # Для локальной Ollama — нужна и LLM-модель.
        check_or_pull_model "$LLM_MODEL" "LLM"
        ;;
    zai)
        # Для cloud z.ai — нужен API key. CLI_AGENT_API_KEY читается cli-agent'ом из env.
        if [[ -z "${CLI_AGENT_API_KEY:-}" ]]; then
            die "Cloud z.ai требует CLI_AGENT_API_KEY. Установите env:
               export CLI_AGENT_API_KEY='your_zai_key'
               Либо переключитесь на локальную Ollama: --provider ollama"
        fi
        ok "LLM: $LLM_MODEL (cloud z.ai) — API key установлен"
        ;;
esac

# ── seed ──────────────────────────────────────────────────────────────────────
if [[ "$NO_SEED" == "true" ]]; then
    log "Пропуск seed (--no-seed)"
else
    if [[ "$DRY_RUN" == "true" ]]; then
        log "[dry-run] Seed: ./gradlew :support-app:run --args=\"seed --reset\""
    else
        log "Seed демо-данных (20 тикетов + 11 FAQ про мессенджер MAX)..."
        # --reset: пересоздать docs/ (идемпотент; тикеты upsert по id).
        ./gradlew :support-app:run --args="seed --reset" --quiet 2>&1 | prefixed
        ok "Seed завершён. Данные в ${XDG_DATA_HOME:-$HOME/.local/share}/cli-agent/support/"
    fi
fi

# ── RAG-индексация (если индекс устарел/отсутствует, или --reindex) ──────────
SUPPORT_DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/cli-agent/support"
DOCS_DIR="${SUPPORT_DATA_DIR}/docs"
INDEX_FILE="${SUPPORT_DATA_DIR}/rag/index.json"

# Нужно ли перестроить индекс?
need_index=false
if [[ "$NO_INDEX" == "true" ]]; then
    log "Пропуск индексации (--no-index)"
elif [[ "$REINDEX" == "true" ]]; then
    need_index=true
    log "Принудительная реиндексация (--reindex)"
elif [[ ! -f "$INDEX_FILE" ]]; then
    need_index=true
    log "Индекс отсутствует — будет построен"
elif [[ -d "$DOCS_DIR" ]]; then
    # Авто-проверка: новее ли какой-то FAQ-файл чем индекс?
    NEWEST_DOCS=$(find "$DOCS_DIR" -name "*.md" -type f -newer "$INDEX_FILE" 2>/dev/null | head -1)
    if [[ -n "$NEWEST_DOCS" ]]; then
        need_index=true
        log "Индекс устарел (изменён $NEWEST_DOCS) — будет перестроен"
    fi
fi

if [[ "$need_index" == "true" ]]; then
    if [[ "$DRY_RUN" == "true" ]]; then
        log "[dry-run] Index: ./gradlew :support-app:run --args=\"index\" (~2 сек, 76 чанков)"
    else
        log "Индексация FAQ → $INDEX_FILE..."
        ./gradlew :support-app:run --args="index" --quiet 2>&1 | prefixed
        ok "Индекс построен"
    fi
fi

# ── запуск сервера ────────────────────────────────────────────────────────────
if [[ "$NO_SERVER" == "true" ]]; then
    log "Сервер не запускается (--no-server). Завершено."
    exit 0
fi

# Pre-flight: убедиться что порт свободен (или освободить — см. KILL_EXISTING).
# Делается ПОСЛЕ всех проверок зависимостей/моделей — нет смысла убивать рабочий
# процесс, если дальше всё равно упадём на неверном API key.
pre_flight_port_check

if [[ "$DRY_RUN" == "true" ]]; then
    log "[dry-run] Сервер не запущен."
    log "[dry-run] Команда запуска:"
    cat <<EOF
  SUPPORT_PORT=$PORT \\
  SUPPORT_PROVIDER=$PROVIDER \\
  SUPPORT_MODEL=$LLM_MODEL \\
  SUPPORT_RAG_EMBEDDING_MODEL=$EMBED_MODEL \\
  OLLAMA_BASE_URL=$OLLAMA_URL \\
  ./gradlew :support-app:run
EOF
    exit 0
fi

log "Запуск Ktor-сервера на http://localhost:${PORT}..."
log "  Provider:  $PROVIDER"
log "  LLM:       $LLM_MODEL"
log "  Embedder:  $EMBED_MODEL (Ollama)"
log "  Ollama URL: $OLLAMA_URL"
log "  Остановить: Ctrl+C"
echo

# Передаём конфиг через env (SupportAgentFactory.fromEnv читает эти переменные).
export SUPPORT_PORT="$PORT"
export SUPPORT_PROVIDER="$PROVIDER"
export SUPPORT_MODEL="$LLM_MODEL"
export SUPPORT_RAG_EMBEDDING_MODEL="$EMBED_MODEL"
export OLLAMA_BASE_URL="$OLLAMA_URL"

./gradlew :support-app:run
