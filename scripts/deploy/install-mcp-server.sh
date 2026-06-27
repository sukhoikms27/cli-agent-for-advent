#!/usr/bin/env bash
#
# install-mcp-server.sh — установка MCP-сервера на VPS (Ubuntu/Debian, systemd).
#
# Что делает (идемпотентно — безопасно перезапускать):
#   1. Ставит OpenJDK 17 JRE (headless), если нет.
#   2. Создаёт системного пользователя `mcp` с домашним каталогом /opt/mcp.
#   3. Кладёт JAR из $1 (аргумент) или из /tmp/mcp-server.jar в /opt/mcp/mcp-server.jar.
#   4. Генерирует /opt/mcp/mcp.env с секретами (MCP bearer + GitHub PAT) с правами 600.
#   5. Создаёт/обновляет systemd-unit /etc/systemd/system/mcp.service (sandboxed).
#   6. enable --now mcp, показывает статус и journalctl.
#
# НЕ делает (отдельный скрипт): firewall + nginx + TLS — см. setup-nginx-tls.sh.
#
# Запуск НА VPS (через ssh) от root/sudo:
#   sudo bash install-mcp-server.sh /tmp/mcp-server.jar
#
set -euo pipefail

# ── конфигурация (можно править под свой сетап) ─────────────────────────────────
MCP_USER="mcp"
MCP_HOME="/opt/mcp"
JAR_DST="${MCP_HOME}/mcp-server.jar"
ENV_FILE="${MCP_HOME}/mcp.env"
UNIT_FILE="/etc/systemd/system/mcp.service"
SERVICE_NAME="mcp"
MCP_BIND_HOST="127.0.0.1"   # слушать только loopback — наружу смотрит nginx
MCP_PORT="8080"
MCP_PATH="/mcp"

# ── хелперы ─────────────────────────────────────────────────────────────────────
log()  { printf '\033[1;32m[install]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n'   "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n'   "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Запустите через sudo / от root."

# ── 1. JDK 17 ───────────────────────────────────────────────────────────────────
if ! command -v java >/dev/null 2>&1; then
    log "OpenJDK не найден — устанавливаю openjdk-17-jre-headless..."
    apt-get update -qq && apt-get install -y openjdk-17-jre-headless
fi
JAVA_VER="$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)"
[[ "${JAVA_VER:-0}" -ge 17 ]] || die "Нужен JDK 17+, обнаружена версия '${JAVA_VER:-?}'. Поставьте: apt install openjdk-17-jre-headless"
log "JDK OK: $(java -version 2>&1 | head -1)"

# ── 2. Пользователь mcp и каталог ───────────────────────────────────────────────
if ! id "$MCP_USER" >/dev/null 2>&1; then
    log "Создаю системного пользователя $MCP_USER (home=$MCP_HOME)..."
    useradd -r -m -d "$MCP_HOME" -s /usr/sbin/nologin "$MCP_USER"
fi
mkdir -p "$MCP_HOME"
chown "${MCP_USER}:${MCP_USER}" "$MCP_HOME"

# ── 3. JAR-артефакт ─────────────────────────────────────────────────────────────
JAR_SRC="${1:-/tmp/mcp-server.jar}"
if [[ ! -f "$JAR_SRC" ]]; then
    die "JAR не найден: '$JAR_SRC'. Скопируйте артефакт на VPS, например:
       scp mcp-server/build/libs/mcp-server-0.1.0-all.jar <user>@<vps>:/tmp/mcp-server.jar
       и повторите: sudo bash $0 /tmp/mcp-server.jar"
fi
log "Копирую $JAR_SRC → $JAR_DST"
install -m644 -o "$MCP_USER" -g "$MCP_USER" "$JAR_SRC" "$JAR_DST"

# ── 4. Секреты (mcp.env) ────────────────────────────────────────────────────────
# MCP bearer — генерируется автоматически (openssl). GitHub PAT — вводит оператор.
# Прим.: погодные env ОТСУТСТВУЮТ намеренно — расписание задаёт агент через MCP-tools
# (subscribe_weather), а не оператор через env (Day 18 redesign, R0-R5).
write_env_file() {
    local mcp_token gh_token
    mcp_token="$(openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64)"

    echo "" >&2
    echo "  MCP_TOKEN (bearer для клиента):  $mcp_token" >&2
    echo "  СОХРАНИТЕ ЕГО — понадобится клиенту (CLI_AGENT_MCP_TOKEN)." >&2
    echo "" >&2
    read -r -p "  Вставьте GitHub PAT (fine-grained, read-only, Public Repos): " gh_token
    [[ -n "$gh_token" ]] || die "GitHub PAT пуст — без него get_repo вернёт tool-error."

    install -m600 -o "$MCP_USER" -g "$MCP_USER" /dev/stdin "$ENV_FILE" <<EOF
CLI_AGENT_MCP_MODE=http
CLI_AGENT_MCP_HOST=${MCP_BIND_HOST}
CLI_AGENT_MCP_PORT=${MCP_PORT}
CLI_AGENT_MCP_PATH=${MCP_PATH}
CLI_AGENT_MCP_TOKEN=${mcp_token}
CLI_AGENT_GITHUB_TOKEN=${gh_token}
EOF
    log "Создан $ENV_FILE (права 600, владелец $MCP_USER)"
}

if [[ -f "$ENV_FILE" ]]; then
    log "Найден существующий $ENV_FILE."
    read -r -p "Пересоздать секреты (старые MCP_TOKEN/GitHub PAT будут утеряны)? [y/N] " ans
    case "${ans,,}" in y|yes|д|да) write_env_file;; *) log "Оставляю существующий env.";; esac
else
    write_env_file
fi

# ── 5. systemd-unit (sandboxed) ─────────────────────────────────────────────────
# ReadWritePaths=/opt/mcp — туда пишет WeatherStore (домашний каталог юзера mcp →
# /opt/mcp/.local/share/cli-agent/weather/). Остальное — read-only через ProtectSystem=strict.
log "Устанавливаю systemd-unit $UNIT_FILE..."
install -m644 /dev/stdin "$UNIT_FILE" <<EOF
[Unit]
Description=CLI-agent MCP server (Streamable HTTP, systemd)
After=network.target

[Service]
Type=simple
User=${MCP_USER}
Group=${MCP_USER}
WorkingDirectory=${MCP_HOME}
EnvironmentFile=${ENV_FILE}
ExecStart=/usr/bin/java -jar ${JAR_DST}
Restart=on-failure
RestartSec=3

# Sandbox (hardening) — принцип наименьших привилегий
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=${MCP_HOME}

[Install]
WantedBy=multi-user.target
EOF

# ── 6. enable + start ───────────────────────────────────────────────────────────
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
systemctl restart "$SERVICE_NAME"
sleep 2

log "Готово. Статус сервиса:"
systemctl --no-pager --full status "$SERVICE_NAME" || true

echo "" >&2
log "Быстрая проверка (должно быть HTTP 401 — bearer не передан):"
curl -s -o /dev/null -w "  без токена: HTTP %{http_code}\n" "http://127.0.0.1:${MCP_PORT}${MCP_PATH}" || true

echo "" >&2
log "Логи сервиса:     sudo journalctl -u $SERVICE_NAME -n 50 --no-pager"
log "Перезапуск:        sudo systemctl restart $SERVICE_NAME"
log "Следующий шаг:    sudo bash setup-nginx-tls.sh <домен-или-IP>   (TLS-прокси наружу)"
