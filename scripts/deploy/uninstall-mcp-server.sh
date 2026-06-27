#!/usr/bin/env bash
#
# uninstall-mcp-server.sh — чистое удаление MCP-сервера с VPS.
#
# Удаляет: systemd-unit + сервис, /opt/mcp/mcp.env, JAR, бэкапы JAR.
# Опционально (с флагом --purge): накопленные данные WeatherStore (weather/*.json) +
#   пользователь mcp и каталог /opt/mcp целиком.
#
# НЕ трогает: nginx-конфиг и сертификаты (это отдельная инфраструктура; убирайте вручную
#   при необходимости: rm /etc/nginx/sites-enabled/mcp).
#
# Запуск НА VPS от root/sudo:
#   sudo bash uninstall-mcp-server.sh            # мягкое: сервис+env+jar, данные сохранить
#   sudo bash uninstall-mcp-server.sh --purge    # полное: + данные weather + user mcp + /opt/mcp
#
set -euo pipefail

# ── конфигурация ────────────────────────────────────────────────────────────────
MCP_USER="mcp"
MCP_HOME="/opt/mcp"
JAR_DST="${MCP_HOME}/mcp-server.jar"
ENV_FILE="${MCP_HOME}/mcp.env"
UNIT_FILE="/etc/systemd/system/mcp.service"
SERVICE_NAME="mcp"

# ── хелперы ─────────────────────────────────────────────────────────────────────
log()  { printf '\033[1;32m[uninstall]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n'   "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n'   "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Запустите через sudo / от root."

PURGE="false"
[[ "${1:-}" == "--purge" ]] && PURGE="true"

# ── подтверждение ───────────────────────────────────────────────────────────────
if [[ "$PURGE" == "true" ]]; then
    warn "Режим --purge: будут удалены также накопленные данные погоды (weather/*.json),"
    warn "пользователь $MCP_USER и каталог $MCP_HOME целиком. Это необратимо."
    echo "" >&2
    read -r -p "Подтвердите полное удаление [yes/NO] " ans
    [[ "${ans,,}" == "yes" || "${ans,,}" == "да" ]] || die "Отменено пользователем."
else
    log "Мягкое удаление: сервис + env + jar + бэкапы. Данные погоды СОХРАНЯЮТСЯ."
    log "(для полного удаления включая данные: sudo bash $0 --purge)"
fi

# ── 1. systemd: stop + disable + rm unit ───────────────────────────────────────
if systemctl list-unit-files | grep -q "^${SERVICE_NAME}\.service"; then
    log "Останавливаю и отключаю сервис $SERVICE_NAME..."
    systemctl stop "$SERVICE_NAME" 2>/dev/null || true
    systemctl disable "$SERVICE_NAME" 2>/dev/null || true
fi
if [[ -f "$UNIT_FILE" ]]; then
    log "Удаляю systemd-unit $UNIT_FILE"
    rm -f "$UNIT_FILE"
    systemctl daemon-reload
fi

# ── 2. секреты + JAR + бэкапы ───────────────────────────────────────────────────
if [[ -f "$ENV_FILE" ]]; then
    log "Удаляю секреты $ENV_FILE"
    rm -f "$ENV_FILE"
fi
if [[ -f "$JAR_DST" ]]; then
    log "Удаляю JAR $JAR_DST"
    rm -f "$JAR_DST"
fi
if ls "${JAR_DST}".*.bak >/dev/null 2>&1; then
    log "Удаляю бэкапы JAR..."
    rm -f "${JAR_DST}".*.bak
fi

# ── 3. --purge: данные + пользователь + каталог ─────────────────────────────────
if [[ "$PURGE" == "true" ]]; then
    # Данные WeatherStore живут в домашнем каталоге юзера mcp (~/.local/share/cli-agent/weather).
    # Удаление пользователя с домашним каталогом убирает и их.
    if id "$MCP_USER" >/dev/null 2>&1; then
        log "Удаляю пользователя $MCP_USER и его домашний каталог $MCP_HOME (включая данные погоды)..."
        userdel -r "$MCP_USER" 2>/dev/null || true
    fi
    if [[ -d "$MCP_HOME" ]]; then
        log "Удаляю каталог $MCP_HOME..."
        rm -rf "$MCP_HOME"
    fi
else
    # Мягкий режим: показать, где остались данные погоды (для информации).
    if id "$MCP_USER" >/dev/null 2>&1; then
        WEATHER_DIR="${MCP_HOME}/.local/share/cli-agent/weather"
        if [[ -d "$WEATHER_DIR" ]] && ls "$WEATHER_DIR"/*.json >/dev/null 2>&1; then
            count=$(ls -1 "$WEATHER_DIR"/*.json 2>/dev/null | wc -l)
            log "Данные погоды сохранены: $WEATHER_DIR ($count файл(ов))."
            log "Для удаления данных: sudo bash $0 --purge"
        fi
    fi
fi

# ── итог ────────────────────────────────────────────────────────────────────────
echo "" >&2
log "Готово. MCP-сервер удалён."
warn "nginx-конфиг ($(/etc/nginx/sites-enabled/mcp 2>/dev/null && echo 'есть' || echo 'не найден')) и сертификаты НЕ тронуты."
log "При необходимости уберите nginx вручную: sudo rm /etc/nginx/sites-enabled/mcp && sudo systemctl reload nginx"
