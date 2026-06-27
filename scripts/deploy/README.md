# Скрипты деплоя MCP-сервера на VPS

Идемпотентные bash-скрипты для развёртывания `:mcp-server` (Streamable HTTP) на VPS под systemd.
Заменяют набор разрозненных команд из `plan/finisheddays/day-17-vps/vps-deploy.md` повторяемыми
скриптами. Учтён Day 18 redesign: **расписание погоды задаёт агент через MCP-tools**, никаких
погодных env-переменных.

## Скрипты

| Скрипт | Назначение |
|---|---|
| `install-mcp-server.sh` | **Основной.** Идемпотентная установка: JDK 17 → user `mcp` → JAR → `mcp.env` (секреты) → systemd-unit (sandboxed) → enable/start → проверка (401) |
| `setup-nginx-tls.sh` | nginx reverse-proxy + TLS. Ветвь **A)** домен + Let's Encrypt (прод); **B)** только IP + self-signed (dev, с предупреждением) |
| `update-jar.sh` | **Быстрый redeploy** JAR без пересоздания секретов: stop → atomic swap → start → health-check + авто-откат при сбое |
| `uninstall-mcp-server.sh` | Чистое удаление. Мягкий режим (сервис+env+jar, данные сохранить) или `--purge` (полное, с данными погоды и user `mcp`) |

## Быстрый старт (первый деплой)

```bash
# ── На машине-разработчике ──────────────────────────────────────────────────────
./gradlew :mcp-server:shadowJar
scp mcp-server/build/libs/mcp-server-0.1.0-all.jar <vps-user>@<vps-ip>:/tmp/mcp-server.jar

# ── На VPS (по ssh) ─────────────────────────────────────────────────────────────
sudo bash scripts/deploy/install-mcp-server.sh /tmp/mcp-server.jar   # 1. сервис (JDK+user+env+systemd)
sudo bash scripts/deploy/setup-nginx-tls.sh mcp.example.ru           # 2. TLS-прокси (домен + Let's Encrypt)
```

После `install-mcp-server.sh` скрипт выведет сгенерированный `MCP_TOKEN` (bearer) — **сохраните его**,
он нужен клиенту.

## Итерации по коду (новый JAR)

```bash
# На машине-разработчике:
./gradlew :mcp-server:shadowJar
scp mcp-server/build/libs/mcp-server-0.1.0-all.jar <vps-user>@<vps-ip>:/tmp/mcp-server.jar

# На VPS:
sudo bash scripts/deploy/update-jar.sh /tmp/mcp-server.jar
#   → бэкап старого JAR → stop → swap → start → health-check
#   → при сбое старта: подсказка команды отката
```

## Архитектура деплоя

```
Клиент (CLI-агент)                  VPS
   │  HTTPS                              nginx :443 (TLS terminate)
   │── POST/GET /mcp ────────────────────►│  proxy_pass http://127.0.0.1:8080
   │◄─────────────────────────────────────│        │
   │  Authorization: Bearer <MCP_TOKEN>   │        ▼
   │                                   systemd: java -jar mcp-server.jar (user mcp, /opt/mcp)
   │                                      │  CLI_AGENT_MCP_TOKEN=<bearer>
   │                                      │  CLI_AGENT_GITHUB_TOKEN=<PAT> ──► GitHub API
   │                                      └─ WeatherStore → /opt/mcp/.local/share/cliagent/weather/
```

- **8080 закрыт наружу** — слушает только `127.0.0.1`, наружу смотрит nginx на 443.
- **GitHub-токен только на сервере** — клиенту нужен лишь MCP bearer (security-улучшение vs stdio).
- **Sandbox systemd**: `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `NoNewPrivileges`,
  `ReadWritePaths=/opt/mcp` (туда пишет `WeatherStore`).

## Env-переменные (только на сервере, в `/opt/mcp/mcp.env`)

| Env | Назначение |
|---|---|
| `CLI_AGENT_MCP_MODE` | `http` (dual-mode сервер, Day 17-vps) |
| `CLI_AGENT_MCP_HOST` | `127.0.0.1` (loopback — наружу nginx) |
| `CLI_AGENT_MCP_PORT` | `8080` |
| `CLI_AGENT_MCP_PATH` | `/mcp` |
| `CLI_AGENT_MCP_TOKEN` | bearer (генерируется скриптом, нужен клиенту) |
| `CLI_AGENT_GITHUB_TOKEN` | GitHub PAT (fine-grained, read-only) — вводит оператор |

> **Погодных env НЕТ.** Расписание periodic-сбора погоды агент регистрирует через MCP-tools
> (`subscribe_weather`/`list_weather_subscriptions`/`unsubscribe_weather`) — Day 18 redesign (R0–R5).
> Сервер стартует с пустым scheduler'ом.

## Клиентские переменные

```bash
export CLI_AGENT_MCP_URL=https://mcp.example.ru/mcp   # публичный эндпоинт
export CLI_AGENT_MCP_TOKEN=<bearer из /opt/mcp/mcp.env>
# GitHub-токен клиенту НЕ нужен — он на сервере.
```

## Чек-лист после установки

- [ ] `sudo systemctl status mcp` — active (running)
- [ ] `curl http://127.0.0.1:8080/mcp` → HTTP **401** (bearer не передан — auth работает)
- [ ] `curl -H "Authorization: Bearer <token>" http://127.0.0.1:8080/mcp` → не 401 (bearer принят)
- [ ] `sudo journalctl -u mcp -n 30 --no-pager` — нет ERROR, виден запуск `cli-agent-mcp`
- [ ] `curl -sk https://mcp.example.ru/mcp` → 401 (TLS-прокси работает)
- [ ] Клиент подключается (`CLI_AGENT_MCP_URL` + `_TOKEN`) → `tools/list` возвращает 7 tools

## Известные ограничения / нюансы

1. **Self-signed TLS сейчас не работает с клиентом.** Ветвь B (`setup-nginx-tls.sh` без домена)
   честно предупреждает: флаг `CLI_AGENT_MCP_INSECURE_TLS` упомянут в `vps-deploy.md`, но **не
   реализован** в `McpClient` (расхождение doc↔code). Для dev без домена используйте **SSH-туннель**:
   ```bash
   ssh -L 8080:127.0.0.1:8080 <vps-user>@<vps-ip> -N
   # клиент: CLI_AGENT_MCP_URL=http://localhost:8080/mcp
   ```
   Для прод — ветвь A (домен + Let's Encrypt).

2. **Только Ubuntu/Debian.** JDK ставится через `apt` (`openjdk-17-jre-headless`). Для RHEL/Fedora
   замените на `dnf install java-17-openjdk-headless`.

3. **JDK проверка по major-версии.** Если на VPS уже стоит JDK 8 — скрипт остановится с ошибкой
   «Нужен JDK 17+». Поставьте 17 или укажите путь через `JAVA_HOME` (не реализовано в скрипте —
   предполагается системный `java`).

4. **Данные погоды переживают мягкий uninstall.** `uninstall-mcp-server.sh` без `--purge` сохраняет
   `/opt/mcp/.local/share/cliagent/weather/`. Полное удаление — флаг `--purge`.

5. **nginx-конфиг и сертификаты не удаляются uninstall-скриптом** — это отдельная инфраструктура.
   При необходимости: `sudo rm /etc/nginx/sites-enabled/mcp && sudo systemctl reload nginx`.

## Полезные команды

```bash
sudo systemctl status mcp                              # статус
sudo journalctl -u mcp -n 50 --no-pager                # логи (последние 50)
sudo journalctl -u mcp -f                              # логи (live follow)
sudo systemctl restart mcp                             # перезапуск
sudo cat /opt/mcp/mcp.env                              # посмотреть env (нужен bearer)
certbot renew --dry-run                                # проверка авто-обновления TLS
```
