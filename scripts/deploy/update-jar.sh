#!/usr/bin/env bash
#
# update-jar.sh — быстрый redeploy MCP-сервера без пересоздания секретов/env.
#
# Для итераций по коду: собрал новый JAR → scp на VPS → этот скрипт.
# Не трогает /opt/mcp/mcp.env и systemd-unit — только бинарник. atomic swap + health-check.
#
# Запуск НА VPS от root/sudo:
#   sudo bash update-jar.sh /tmp/mcp-server.jar
#
set -euo pipefail

# ── конфигурация ────────────────────────────────────────────────────────────────
MCP_USER="mcp"
MCP_HOME="/opt/mcp"
JAR_DST="${MCP_HOME}/mcp-server.jar"
SERVICE_NAME="mcp"
HEALTH_URL="http://127.0.0.1:8080/mcp"
HEALTH_TIMEOUT=15   # секунд на старт JVM

# ── хелперы ─────────────────────────────────────────────────────────────────────
log()  { printf '\033[1;32m[update]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n'   "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n'   "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Запустите через sudo / от root."

JAR_SRC="${1:-/tmp/mcp-server.jar}"
[[ -f "$JAR_SRC" ]] || die "JAR не найден: '$JAR_SRC'. Скопируйте артефакт:
   scp mcp-server/build/libs/mcp-server-0.1.0-all.jar <user>@<vps>:/tmp/mcp-server.jar"

# ── предусловие: сервис должен быть установлен ──────────────────────────────────
[[ -f "$JAR_DST" ]] || die "$JAR_DST не существует — сервис не установлен. Запустите install-mcp-server.sh."
systemctl list-unit-files | grep -q "^${SERVICE_NAME}\.service" || die "systemd-unit ${SERVICE_NAME} не найден — запустите install-mcp-server.sh."

# ── бэкап текущего JAR (откат) ──────────────────────────────────────────────────
BACKUP="${JAR_DST}.$(date +%Y%m%d-%H%M%S).bak"
log "Бэкап текущего JAR → $BACKUP"
cp -a "$JAR_DST" "$BACKUP"

# ── stop → atomic swap → start ──────────────────────────────────────────────────
log "Останавливаю сервис..."
systemctl stop "$SERVICE_NAME" || true

log "Заменяю JAR ($JAR_SRC → $JAR_DST)..."
install -m644 -o "$MCP_USER" -g "$MCP_USER" "$JAR_SRC" "$JAR_DST"

log "Запускаю сервис..."
systemctl start "$SERVICE_NAME"

# ── health-check: ждём, пока JVM поднимется и /mcp ответит 401 ───────────────────
log "Health-check (жду HTTP-ответ от $HEALTH_URL, таймаут ${HEALTH_TIMEOUT}s)..."
code=""
for i in $(seq 1 "$HEALTH_TIMEOUT"); do
    code="$(curl -s -o /dev/null -w "%{http_code}" "$HEALTH_URL" 2>/dev/null || true)"
    if [[ -n "$code" && "$code" != "000" ]]; then
        break
    fi
    sleep 1
done

if [[ -z "$code" || "$code" == "000" ]]; then
    warn "Сервис не ответил за ${HEALTH_TIMEOUT}s. Последние логи:"
    journalctl -u "$SERVICE_NAME" -n 20 --no-pager >&2 || true
    die "Старт не удался. Откат: sudo cp '$BACKUP' '$JAR_DST' && sudo systemctl start $SERVICE_NAME"
fi

log "Сервис отвечает: HTTP $code (401 = ок, bearer не передан в health-check)."

# ── cleanup старых бэкапов (хранить последние 5) ────────────────────────────────
log "Чищу старые бэкапы (оставляю последние 5)..."
ls -1t "${JAR_DST}".*.bak 2>/dev/null | tail -n +6 | while read -r old; do
    rm -f "$old"
done

echo "" >&2
log "Готово. Откат при необходимости: sudo cp '$BACKUP' '$JAR_DST' && sudo systemctl restart $SERVICE_NAME"
log "Логи: sudo journalctl -u $SERVICE_NAME -n 30 --no-pager"
