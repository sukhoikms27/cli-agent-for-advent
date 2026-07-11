#!/usr/bin/env bash
# День 30 — Deploy script: приватный LLM-сервис на VPS (Ollama + Caddy)
#
# Запускать НА VPS (после SSH-hardening, см. .artifacts/week-06/day-30-draft.md Этап 0).
# Скрипт: ставит Ollama, создаёт VPS-модель, ставит Caddy reverse proxy.
#
# Usage:
#   ./deploy/vps/deploy.sh --domain llm.example.ru --token ВАШ_ТОКЕН
#
# Требования: Ubuntu/Debian, sudo, домен с A-record → этот VPS.

set -euo pipefail

DOMAIN=""
TOKEN=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --domain) DOMAIN="$2"; shift 2 ;;
        --token)  TOKEN="$2";  shift 2 ;;
        *) echo "Unknown arg: $1"; exit 2 ;;
    esac
done

if [[ -z "$DOMAIN" || -z "$TOKEN" ]]; then
    echo "Usage: $0 --domain llm.example.ru --token SECRET_TOKEN"
    exit 2
fi

echo "=== День 30: приватный LLM-сервис deploy ==="
echo "Domain: $DOMAIN"

# 1. Ollama install
if ! command -v ollama &>/dev/null; then
    echo ">>> Installing Ollama..."
    curl -fsSL https://ollama.com/install.sh | sh
else
    echo ">>> Ollama already installed: $(ollama --version)"
fi

# 2. systemd override (num_parallel=1, keep_alive=30m, bind localhost)
echo ">>> Configuring Ollama systemd override..."
sudo mkdir -p /etc/systemd/system/ollama.service.d
sudo cp "$(dirname "$0")/ollama.service.override.conf" \
    /etc/systemd/system/ollama.service.d/override.conf
sudo systemctl daemon-reload
sudo systemctl enable --now ollama
sudo systemctl restart ollama
echo ">>> Ollama restarted with override (num_parallel=1, keep_alive=30m, bind 127.0.0.1)"

# 3. Pull model + create VPS-optimized variant (num_ctx 8192)
echo ">>> Pulling qwen2.5:7b-instruct-q5_K_M (~5 GB, 1-3 min on NVMe)..."
ollama pull qwen2.5:7b-instruct-q5_K_M
echo ">>> Creating VPS-optimized model (num_ctx 8192, num_predict 2048)..."
ollama create qwen2.5-7b-vps -f "$(dirname "$0")/Modelfile.qwen25-7b"

# Smoke-test движка (на VPS, через localhost)
echo ">>> Smoke-test: qwen2.5-7b-vps..."
ollama run qwen2.5-7b-vps "Say hello in one word."
echo ""

# 4. Caddy reverse proxy (auto-HTTPS + Bearer auth)
echo ">>> Installing Caddy..."
sudo apt-get update -qq
sudo apt-get install -y -qq caddy

# Caddyfile с доменом и токеном
TMP_CADDYFILE=$(mktemp)
sed "s|llm.ВАШ-ДОМЕН.ru|$DOMAIN|g" "$(dirname "$0")/Caddyfile" > "$TMP_CADDYFILE"
sudo cp "$TMP_CADDYFILE" /etc/caddy/Caddyfile
rm "$TMP_CADDYFILE"

# Передать токен в Caddy через systemd override
sudo systemctl edit --force caddy <<EOF
[Service]
Environment="LLM_TOKEN=$TOKEN"
EOF
sudo systemctl restart caddy
echo ">>> Caddy restarted (domain=$DOMAIN, auth=Bearer token)"

# 5. Firewall
echo ">>> Configuring ufw firewall..."
sudo ufw allow 22/tcp       # SSH
sudo ufw allow 80/tcp       # HTTP (Caddy redirect → HTTPS)
sudo ufw allow 443/tcp      # HTTPS (Caddy)
sudo ufw --force enable
echo ">>> Firewall: 22/80/443 open, 11434 NOT exposed (bind 127.0.0.1)"

echo ""
echo "=== Deploy complete ==="
echo "Service:   https://$DOMAIN/v1/chat/completions (OpenAI-compatible)"
echo "Auth:      Authorization: Bearer $TOKEN"
echo ""
echo "CLI-агент подключение:"
echo "  CLI_AGENT_PROVIDER=ollama \\"
echo "  CLI_AGENT_BASE_URL=https://$DOMAIN/v1 \\"
echo "  CLI_AGENT_MODEL=qwen2.5-7b-vps \\"
echo "  CLI_AGENT_API_KEY=$TOKEN \\"
echo "  ./gradlew run --args=\"chat\""
echo ""
echo "Проверка (curl):"
echo "  curl https://$DOMAIN/v1/models -H 'Authorization: Bearer $TOKEN'"
