# День 18 — Деплой MCP-сервера на VPS (Streamable HTTP)

Полная инструкция по развёртыванию `:mcp-server` (dual-mode, remote Streamable HTTP) на VPS.
Артефакт: `mcp-server/build/libs/mcp-server-0.1.0-all.jar` (fat-jar, собирается `./gradlew :mcp-server:shadowJar`).

> Это шпаргалка команд. Бóльшая часть выполняется **на VPS** по SSH; блок 3 (scp) — на вашей машине.

## Принципиальная схема

```
Локальная машина (CLI-агент)        VPS
   │  HTTPS (Streamable HTTP)          nginx :443 (TLS, terminate)
   │── POST/GET /mcp ──────────────────►│  proxy_pass 127.0.0.1:8080
   │◄───────────────────────────────────│        │
   │   Authorization: Bearer <MCP_TOKEN>│        ▼
   │                                    │  java -jar mcp-server.jar (systemd, mcp user)
   │                                    │   CLI_AGENT_MCP_MODE=http
   │                                    │   CLI_AGENT_MCP_TOKEN=<bearer>
   │                                    │   CLI_AGENT_GITHUB_TOKEN=<PAT>  ──► GitHub API
```

GitHub-токен держится **только на сервере**; клиенту нужен лишь MCP bearer — улучшение безопасности
относительно локального stdio.

## БЛОК 1 — JDK 17 (VPS, один раз)
```bash
sudo apt update && sudo apt install -y openjdk-17-jre-headless   # Debian/Ubuntu
# sudo dnf install -y java-17-openjdk-headless                    # RHEL/Fedora
java -version   # 17.x
```

## БЛОК 2 — Пользователь и каталоги (VPS, один раз)
```bash
sudo useradd -r -m -d /opt/mcp -s /usr/sbin/nologin mcp
sudo mkdir -p /opt/mcp && sudo chown mcp:mcp /opt/mcp
```

## БЛОК 3 — Загрузка артефакта (с локальной машины → VPS)
```bash
# ЛОКАЛЬНО:
scp mcp-server/build/libs/mcp-server-0.1.0-all.jar <vps-user>@<vps-ip>:/tmp/mcp-server.jar
# НА VPS:
sudo mv /tmp/mcp-server.jar /opt/mcp/mcp-server.jar
sudo chown mcp:mcp /opt/mcp/mcp-server.jar && sudo chmod 644 /opt/mcp/mcp-server.jar
```

## БЛОК 4 — Секреты (VPS, один раз)
```bash
MCP_TOKEN=$(openssl rand -base64 32)
echo "СОХРАНИТЕ MCP_TOKEN для клиента: $MCP_TOKEN"
sudo install -m600 -o mcp -g mcp /dev/stdin /opt/mcp/mcp.env <<EOF
CLI_AGENT_MCP_MODE=http
CLI_AGENT_MCP_HOST=127.0.0.1
CLI_AGENT_MCP_PORT=8080
CLI_AGENT_MCP_PATH=/mcp
CLI_AGENT_MCP_TOKEN=$MCP_TOKEN
CLI_AGENT_GITHUB_TOKEN=ghp_ВАШ_GITHUB_PAT
EOF
sudo chmod 600 /opt/mcp/mcp.env && sudo chown mcp:mcp /opt/mcp/mcp.env
```
GitHub PAT: fine-grained, read-only, scope *Public Repositories (read-only)*.

## БЛОК 5 — systemd-unit (VPS, один раз)
```bash
sudo install -m644 /dev/stdin /etc/systemd/system/mcp.service <<'EOF'
[Unit]
Description=CLI-agent MCP server (GitHub, Streamable HTTP)
After=network.target

[Service]
Type=simple
User=mcp
Group=mcp
WorkingDirectory=/opt/mcp
EnvironmentFile=/opt/mcp/mcp.env
ExecStart=/usr/bin/java -jar /opt/mcp/mcp-server.jar
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/opt/mcp

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now mcp
```

## БЛОК 6 — Проверка сервиса (VPS)
```bash
sudo systemctl status mcp
sudo journalctl -u mcp -n 30 --no-pager
curl -s -o /dev/null -w "без токена: %{http_code}\n" http://127.0.0.1:8080/mcp        # 401
curl -s -o /dev/null -w "с неверным: %{http_code}\n" -H "Authorization: Bearer wrong" http://127.0.0.1:8080/mcp  # 401
```

## БЛОК 7 — Firewall (VPS)
```bash
sudo apt install -y ufw
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw --force enable && sudo ufw status verbose
```
Порт 8080 наружу **закрыт** — он слушает 127.0.0.1, наружу идёт через nginx.

## БЛОК 8 — Сеть и TLS

### Ветвление A — домен готов (основной путь)
```bash
sudo apt install -y nginx certbot python3-certbot-nginx
sudo install -m644 /dev/stdin /etc/nginx/sites-available/mcp <<'EOF'
server {
    listen 80;
    server_name mcp.ВАШ-ДОМЕН.ru;
    location / { return 301 https://$host$request_uri; }
}
server {
    listen 443 ssl;
    server_name mcp.ВАШ-ДОМЕН.ru;
    location = /mcp {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_buffering off;   # КРИТИЧНО для Streamable HTTP/SSE
        proxy_cache off;
        proxy_read_timeout 1h;
    }
}
EOF
sudo ln -sf /etc/nginx/sites-available/mcp /etc/nginx/sites-enabled/mcp
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d mcp.ВАШ-ДОМЕН.ru
```
URL клиента: `https://mcp.ВАШ-ДОМЕН.ru/mcp`

### Ветвление B — только IP, домена пока нет
**B1 — nginx + self-signed (dev-only):**
```bash
sudo apt install -y nginx
sudo openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout /etc/ssl/private/mcp.key -out /etc/ssl/certs/mcp.crt -subj "/CN=<VPS-IP>"
sudo install -m644 /dev/stdin /etc/nginx/sites-available/mcp <<'EOF'
server {
    listen 443 ssl;
    server_name <VPS-IP>;
    ssl_certificate /etc/ssl/certs/mcp.crt;
    ssl_certificate_key /etc/ssl/private/mcp.key;
    location = /mcp {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 1h;
    }
}
EOF
sudo ln -sf /etc/nginx/sites-available/mcp /etc/nginx/sites-enabled/mcp
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```
URL клиента: `https://<VPS-IP>/mcp`. ⚠️ Клиенту нужно доверять self-signed сертификату —
в `McpClient` есть флаг `CLI_AGENT_MCP_INSECURE_TLS=1` (только dev).

**B2 — SSH-туннель (проще, без TLS):**
```bash
# ЛОКАЛЬНО:
ssh -L 8080:127.0.0.1:8080 <vps-user>@<vps-ip> -N
# клиент стучится на http://localhost:8080/mcp
```

**Миграция B→A:** при появлении домена — `certbot --nginx -d <домен>`, сменить клиентский `CLI_AGENT_MCP_URL`.
Кода сервера это не касается.

## Переменные клиента (Этап 5)
```bash
export CLI_AGENT_MCP_URL=https://mcp.ВАШ-ДОМЕН.ru/mcp   # A  (или B2: http://localhost:8080/mcp)
export CLI_AGENT_MCP_TOKEN=<тот самый MCP_TOKEN из Блока 4>
# GitHub-токен клиенту НЕ нужен — он на сервере.
```
