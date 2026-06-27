#!/usr/bin/env bash
#
# setup-nginx-tls.sh — nginx reverse-proxy + TLS для MCP-сервера.
#
# Проксирует https://<host>/mcp → http://127.0.0.1:8080/mcp (systemd-сервис `mcp`).
# proxy_buffering off + proxy_read_timeout 1h — КРИТИЧНО для Streamable HTTP/SSE.
#
# Две ветви:
#   A) домен (FQDN) + Let's Encrypt через certbot — рекомендуется для прод-деплоя.
#   B) только IP + self-signed сертификат — dev-only; ВНИМАНИЕ: клиенту на self-signed
#      HTTPS сейчас упадёт TLS-handshake, т.к. флаг CLI_AGENT_MCP_INSECURE_TLS в коде
#      НЕ реализован (расхождение doc↔code). Для dev без домена лучше SSH-туннель (см. README).
#
# Запуск НА VPS от root/sudo:
#   sudo bash setup-nginx-tls.sh mcp.example.ru      # ветвь A (домен)
#   sudo bash setup-nginx-tls.sh                     # ветвь B (self-signed по IP хоста)
#
set -euo pipefail

# ── конфигурация ────────────────────────────────────────────────────────────────
BACKEND_HOST="127.0.0.1"
BACKEND_PORT="8080"
BACKEND_PATH="/mcp"
NGINX_SITE="/etc/nginx/sites-available/mcp"
NGINX_ENAB="/etc/nginx/sites-enabled/mcp"
NGINX_DEFAULT="/etc/nginx/sites-enabled/default"

# ── хелперы ─────────────────────────────────────────────────────────────────────
log()  { printf '\033[1;32m[nginx]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n'   "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n'   "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Запустите через sudo / от root."

HOSTNAME_ARG="${1:-}"

# ── установка nginx (+ certbot только для ветви A) ──────────────────────────────
if ! command -v nginx >/dev/null 2>&1; then
    log "nginx не найден — устанавливаю..."
    apt-get update -qq && apt-get install -y nginx
fi

# ── определение режима: домен (A) или IP/self-signed (B) ────────────────────────
# Эвристика: содержит точку и не похоже на голый IP → домен (A).
is_ip() { [[ "$1" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; }

if [[ -z "$HOSTNAME_ARG" ]]; then
    # Без аргумента — ветвь B (self-signed по текущему публичному IP хоста).
    MODE="B"
    HOSTNAME_ARG="$(curl -s --max-time 5 ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
    [[ -n "$HOSTNAME_ARG" ]] || die "Не удалось определить IP/hostname. Передайте аргумент: sudo bash $0 <домен-или-IP>"
elif is_ip "$HOSTNAME_ARG"; then
    MODE="B"
else
    MODE="A"
fi

log "Режим: $MODE | host: $HOSTNAME_ARG"

# ── общая конфигурация location /mcp (proxy) ────────────────────────────────────
# Вынесена в функцию, чтобы обе ветви использовали идентичные proxy-настройки.
write_location_block() {
    cat <<LOCATION
        location = ${BACKEND_PATH} {
            proxy_pass http://${BACKEND_HOST}:${BACKEND_PORT};
            proxy_http_version 1.1;
            proxy_set_header Host \$host;
            proxy_set_header Connection "";
            proxy_buffering off;    # КРИТИЧНО для Streamable HTTP/SSE
            proxy_cache off;
            proxy_read_timeout 1h;
        }
LOCATION
}

# ── ветвь A: домен + Let's Encrypt ─────────────────────────────────────────────
if [[ "$MODE" == "A" ]]; then
    log "Устанавливаю certbot + python3-certbot-nginx..."
    apt-get install -y certbot python3-certbot-nginx

    # Сначала HTTP-only server (certbot сам допишет ssl-блок после выдачи сертификата).
    install -m644 /dev/stdin "$NGINX_SITE" <<EOF
server {
    listen 80;
    server_name ${HOSTNAME_ARG};
    location / { return 301 https://\$host\$request_uri; }
}
server {
    listen 443 ssl;
    server_name ${HOSTNAME_ARG};
$(write_location_block)
}
EOF
    ln -sf "$NGINX_SITE" "$NGINX_ENAB"
    rm -f "$NGINX_DEFAULT"

    log "Проверка конфигурации nginx..."
    nginx -t

    log "Перезапуск nginx + запрос сертификата Let's Encrypt для ${HOSTNAME_ARG}..."
    systemctl reload nginx
    # certbot сам поставит ssl_certificate/ssl_certificate_key и настроит редирект 80→443.
    certbot --nginx -d "$HOSTNAME_ARG" --non-interactive --agree-tos -m "admin@${HOSTNAME_ARG#*.}" || \
        warn "certbot не выдал сертификат (DNS A-запись на этот VPS настроена?). HTTP-сервер запущен, но HTTPS требует ручного запуска: certbot --nginx -d ${HOSTNAME_ARG}"

    CLIENT_URL="https://${HOSTNAME_ARG}${BACKEND_PATH}"

# ── ветвь B: только IP + self-signed ───────────────────────────────────────────
else
    warn "Ветвь B: self-signed сертификат. CLI-клиент на такой HTTPS упадёт в handshake"
    warn "(флаг CLI_AGENT_MCP_INSECURE_TLS в коде НЕ реализован — расхождение doc↔code)."
    warn "Для dev без домена рекомендуется SSH-туннель: ssh -L 8080:127.0.0.1:8080 <vps> -N"
    echo "" >&2
    read -r -p "Продолжить с self-signed? [y/N] " ans
    case "${ans,,}" in y|yes|д|да) ;; *) die "Отменено пользователем.";; esac

    log "Генерирую self-signed сертификат для ${HOSTNAME_ARG}..."
    install -d -m755 /etc/ssl/private
    openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
        -keyout /etc/ssl/private/mcp.key -out /etc/ssl/certs/mcp.crt \
        -subj "/CN=${HOSTNAME_ARG}" >/dev/null 2>&1
    chmod 600 /etc/ssl/private/mcp.key

    install -m644 /dev/stdin "$NGINX_SITE" <<EOF
server {
    listen 443 ssl;
    server_name ${HOSTNAME_ARG};
    ssl_certificate     /etc/ssl/certs/mcp.crt;
    ssl_certificate_key /etc/ssl/private/mcp.key;
$(write_location_block)
}
EOF
    ln -sf "$NGINX_SITE" "$NGINX_ENAB"
    rm -f "$NGINX_DEFAULT"

    log "Проверка конфигурации nginx..."
    nginx -t
    systemctl reload nginx

    CLIENT_URL="https://${HOSTNAME_ARG}${BACKEND_PATH}"
fi

# ── firewall: открыть 80/443 (если ufw активен) ─────────────────────────────────
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    log "ufw активен — открываю 80/443 (8080 закрыт, слушает 127.0.0.1)..."
    ufw allow 80/tcp >/dev/null
    ufw allow 443/tcp >/dev/null
fi

# ── итог ────────────────────────────────────────────────────────────────────────
echo "" >&2
log "Готово. Публичный эндпоинт MCP: ${CLIENT_URL}"
log "Проверка прокси (должно быть HTTP 401 — bearer не передан):"
curl -sk -o /dev/null -w "  ${CLIENT_URL}: HTTP %{http_code}\n" "$CLIENT_URL" || true

echo "" >&2
log "Настройте клиент CLI-агента:"
echo "  export CLI_AGENT_MCP_URL=${CLIENT_URL}" >&2
echo "  export CLI_AGENT_MCP_TOKEN=<bearer из /opt/mcp/mcp.env>" >&2
echo "" >&2
log "Перевыпуск сертификата Let's Encrypt: certbot renew (cron уже настроен certbot'ом)."
