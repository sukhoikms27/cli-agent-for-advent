# День 30 — Локальная LLM как приватный сервис

## Краткое резюме

Цель дня — поднять **минимальный рабочий стенд приватного LLM-сервиса**: локальная модель класса 7-8B
на дешёвом CPU-VPS, доступная по HTTPS из CLI-агента, плюс гибридный профиль с managed API для тяжёлых
задач. Это обучение/практика, а не production-деплой на millions of tokens.

**Выбранный сценарий — гибрид:**

- **CPU-VPS** (~€6-8/мес, 4-8 vCPU / 8 GB RAM) с моделью **7-8B Q4_K_M/Q5_K_M** под инференс через
  **Ollama** — для приватных и лёгких задач (offline-first, full data ownership).
- **Managed API** (~$1-3/мес, DeepSeek V3 / Groq Qwen3-32B) — для тяжёлых задач, reasoning и длинных
  контекстов.
- **Итоговый бюджет: ~$8-10/мес.**

CLI-агент на Kotlin уже на ~90% готов к такому сценарию (multi-provider из дня 25:
`LlmClientFactory` + `LlmProvider` enum, OpenAI-compatible wire-формат, env-override), поэтому день
30 — это преимущественно **инфра-деплой и сетевая конфигурация**, а не кодинг. Кодовые правки —
опциональны и изолированы.

> Конвенции проекта (AGENTS.md): env > config.json, JSON-персистентность с `atomicWrite`, sealed
> `LlmResult` для ошибок, корутины (`runBlocking` в `main`, `withContext(Dispatchers.IO)` для Ktor),
> единый `AppJson`, XDG-пути. План следует им.

---

## Архитектурные решения

| Что | Выбор | Обоснование |
|---|---|---|
| Класс модели | **7-8B Instruct** (Qwen2.5-7B-Instruct или Llama 3.1 8B) | При бюджете ≤$15/мес и single-user чате даёт юзабельную скорость 10-15 tok/s на CPU. 14B = 5-6 tok/s (медленно), 32B = 1.5-3 tok/s (НЕюзабельно для чата). |
| Квантование | **GGUF Q4_K_M** (sweet spot), Q5_K_M если RAM позволяет | ~1-3% loss perplexity vs FP16, ~92% качества, файл ~4.7-4.9 GB, ~6 GB RAM при 4K-контексте. Избегать Q2/Q3 (качество падает). |
| Inference-движок | **Ollama** (основной путь) | Wraps llama.cpp, OpenAI-compatible `/v1/chat/completions`, простая установка/управление, **уже есть `LlmProvider.OLLAMA` в коде** (день 25). Альтернатива — `llama-server` (приводится для справки). |
| VPS | **Hetzner CX32** (~€6.5/мес, 4 vCPU/8 GB) — РЕКОМЕНДУЕТСЯ | 8 GB RAM с запасом для 7B Q4 + KV-cache при 8K контексте. NVMe обязателен. Contabo/Vultr — альтернативы. |
| Сетевой фронт | **Reverse proxy: Caddy (auto-HTTPS) ИЛИ Nginx + certbot** | Движок биндится на `127.0.0.1`, прокси терминирует TLS + Bearer auth + rate-limit. Никогда не выставлять порт 11434/8080 напрямую. |
| Гибрид | local Ollama + managed API (DeepSeek/Groq) | Лёгкие/приватные → local, тяжёлые/long-context → managed. Бюджет ~$8-10/мес. |
| GPU | **Не нужен** для 7-8B на этом сценарии | Self-host 32B GPU = $250-500/мес — окупается только >150-800M токенов/мес ИЛИ ради приватности. |

---

## Этап 0 — Выбор VPS и подготовка

### 0.1. Сравнение провайдеров (для 7-8B на CPU)

| Провайдер | Конфиг | Цена | RAM | Заметка |
|---|---|---|---|---|
| **Hetzner Cloud CX32** | 4 vCPU / 8 GB / 80 GB NVMe | **~€6.5/мес** | 8 GB | РЕКОМЕНДУЕТСЯ — стабильный, не oversold, NVMe |
| Hetzner CX22 | 2 vCPU / 4 GB / 40 GB | ~€4/мес | 4 GB | МАЛО для 7B Q4 (нужно ~6-7 GB) |
| Contabo Cloud VPS S | 4 vCPU / 8 GB / 100 GB | ~€5-6/мес | 8 GB | Дешевле, но oversold — возможен noisy neighbour |
| Contabo Cloud VPS M | 6 vCPU / 16 GB | ~€9-12/мес | 16 GB | Запас по RAM, если нужен 14B |
| Vultr High Frequency | 2 vCPU / 8 GB / 128 GB NVMe | ~$24/мес | 8 GB | Дорого для этого сценария |
| Hetzner auction dedicated | 64 GB RAM | ~€45/мес | 64 GB | Overkill для 7B (нужно для 32B+) |

> Цены ориентировочные, проверять актуальные на сайте провайдера (см. «Ссылки на источники»).
> **GPU для 7-8B не нужен.**

### 0.2. Заказ сервера

- Регистрация на [console.hetzner.cloud](https://console.hetzner.cloud), создание CX32 (или
  эквивалент).
- OS: **Ubuntu 24.04 LTS** (или Debian 12).
- Region: ближайший к пользователю (EU для РФ — Falkenstein/Helsinki).
- SSH-ключ: загрузить публичный ключ (НЕ пароль).
- Записать `<VPS-IP>` и `<vps-user>` (обычно `root` на Hetzner сразу, лучше создать non-root).

### 0.3. SSH-hardening чеклист

```bash
# ЛОКАЛЬНО — после создания сервера
ssh root@<VPS-IP>

# НА VPS:
# 1. Создать non-root sudoer (если ещё нет)
adduser cliadmin && usermod -aG sudo cliadmin
# скопировать ssh-ключ для cliadmin: rsync --archive --chown=cliadmin:cliadmin ~/.ssh /home/cliadmin

# 2. Запретить root-login и password-auth
sudo tee /etc/ssh/sshd_config.d/99-hardening.conf >/dev/null <<'EOF'
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
ClientAliveInterval 300
ClientAliveCountMax 2
EOF
sudo systemctl restart sshd

# 3. unattended-upgrades (security auto)
sudo apt update && sudo apt install -y unattended-upgrades fail2ban
sudo systemctl enable --now fail2ban
```

### 0.4. Firewall (ufw)

```bash
sudo apt install -y ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp        # SSH
sudo ufw allow 80/tcp        # HTTP → редирект на HTTPS (certbot http-01)
sudo ufw allow 443/tcp       # HTTPS (reverse proxy)
sudo ufw deny 11434/tcp      # ЯВНО: Ollama не должна торчать наружу
sudo ufw deny 8080/tcp       # ЯВНО: llama-server тоже
sudo ufw --force enable
sudo ufw status verbose
```

> Ollama по умолчанию слушает `127.0.0.1:11434` — изнутри не доступна, но `deny 11434` — второй
> слой защиты на случай ошибки bind.

---

## Этап 1 — Установка inference-движка и модели

### 1.1. Вариант A — Ollama (РЕКОМЕНДУЕТСЯ, основной путь)

```bash
# НА VPS (cliadmin):
curl -fsSL https://ollama.com/install.sh | sh
# Скрипт ставит бинарник в /usr/local/bin/ollama, создаёт ollama.service (systemd),
# пользователя ollama, и хранилище /usr/share/ollama (~/.ollama для юзера ollama).
systemctl status ollama    # active (running)
```

**Проверка после установки (на VPS локально):**
```bash
curl -s http://127.0.0.1:11434/v1/models | jq '.data[].id'
# [] — список пуст, модель ещё не загружена
```

**Pull модели:**
```bash
# 7B Q4_K_M — основной выбор (sweet spot)
ollama pull qwen2.5:7b-instruct-q5_K_M
# или Q4_K_M (чуть меньше RAM):
# ollama pull qwen2.5:7b-instruct-q4_K_M
# или Llama 3.1 8B:
# ollama pull llama3.1:8b-instruct-q4_K_M

# Занимает ~5 GB, на NVMe — 1-3 минуты. Проверить:
ollama list
ollama ps   # загружена ли в RAM сейчас
```

### 1.2. Modelfile — параметры контекста и output

Ollama читает параметры из Modelfile. Для ограничения контекста на дешёвом VPS создаём кастомную
модель-обёртку:

```bash
mkdir -p ~/ollama-models
cat > ~/ollama-models/qwen25-7b-private.Modelfile <<'EOF'
# Обёртка над официальным тегом с явно ограниченным контекстом
FROM qwen2.5:7b-instruct-q5_K_M

# KV-cache растёт линейно с контекстом — главный OOM-предохранитель.
# 8192 достаточно для большинства single-user диалогов + RAG-чанков.
PARAMETER num_ctx 8192
PARAMETER num_predict 2048

# Движок-уровень параллелизма: 1 слот на дешёвом VPS (см. systemd override ниже).
# keep_alive держит модель в RAM; -1 = всегда (для always-on), по умолчанию 5 мин.
PARAMETER keep_alive 30m
EOF

ollama create qwen25-7b-private -f ~/ollama-models/qwen25-7b-private.Modelfile
```

### 1.3. systemd override — env движка

```bash
sudo systemctl edit ollama
# В открывшемся редакторе добавить:
```
```ini
[Service]
# Параллельные слоты: 1 на дешёвом VPS (главный OOM-предохранитель)
Environment="OLLAMA_NUM_PARALLEL=1"
# Держать модель загруженной (default 5m → cold-start 10-30с). Для always-on можно -1.
Environment="OLLAMA_KEEP_ALIVE=30m"
# Bind ТОЛЬКО localhost (поверх default, но явнее)
Environment="OLLAMA_HOST=127.0.0.1:11434"
# CORS: перечислить конкретные origin, ИЗБЕГАТЬ "*" (см. риски)
Environment="OLLAMA_ORIGINS=https://ВАШ-ДОМЕН.ru"
# Ограничения ресурсов (hardening)
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/usr/share/ollama /var/log
```
```bash
sudo systemctl daemon-reload && sudo systemctl restart ollama
journalctl -u ollama -n 20 --no-pager
```

### 1.4. Smoke-test движка (на VPS)

```bash
# Список моделей через OpenAI-compat endpoint
curl -s http://127.0.0.1:11434/v1/models | jq '.data[].id'
# → "qwen25-7b-private:latest"

# Простой чат через OpenAI-compat /v1/chat/completions
curl -s http://127.0.0.1:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen25-7b-private",
    "messages": [{"role":"user","content":"Сколько будет 2+2? Ответь одной цифрой."}],
    "max_tokens": 16,
    "temperature": 0
  }' | jq '.choices[0].message.content'
# → "4"
```

### 1.5. Вариант B — llama.cpp `llama-server` (альтернатива, для справки)

Если нужен тонкий контроль над флагами CPU-оптимизации или не-Ollama стек:

```bash
# Сборка с AVX2 (CPU-оптимизация)
sudo apt install -y build-essential cmake git
git clone https://github.com/ggerganov/llama.cpp && cd llama.cpp
cmake -B build -DLLAMA_NATIVE=ON && cmake --build build --config Release -j

# Скачать GGUF (Hugging Face, нужна учётка для Llama/Qwen gated-repos; Qwen открыт)
mkdir -p ~/models && cd ~/models
wget https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q5_k_m.gguf

# Запуск с CPU-оптимизациями:
./build/bin/llama-server \
  -m ~/models/qwen2.5-7b-instruct-q5_k_m.gguf \
  --host 127.0.0.1 --port 8080 \
  --alias qwen2.5-7b \
  -c 8192 -np 1 \
  -fa \
  -ctk q8_0 -ctv q8_0 \
  --mmap
```

**Объяснение флагов CPU-оптимизации (применимы и к Ollama под капотом):**

| Флаг | Эффект | Почему важно на CPU-VPS |
|---|---|---|
| `--mmap` (default в Ollama/llama.cpp) | memory-mapping весов, lazy load в RAM, clean pages | Позволяет грузить модели крупнее RAM, не изнашивает NVMe (в отличие от swap) |
| `-fa` / `--flash-attn` | flash attention | 1.3-2× speedup prompt processing + меньше KV-cache memory |
| `-ctk q8_0 -ctv q8_0` | KV-cache quantization | Halves KV-cache memory, ~1% quality loss — критично при ограниченной RAM |
| `-c 8192` | cap context window | KV растёт линейно с контекстом, убивает throughput и RAM |
| `-np 1` | 1 parallel slot | На дешёвом VPS хватит для single-user; больше слотов = больше RAM под KV |

> **Выбор для минимального стенда: Ollama** — её параметры (`num_ctx`, `num_predict`,
> `keep_alive`, `OLLAMA_NUM_PARALLEL`) покрывают большинство случаев без ручных флагов llama.cpp.
> Вариант B — для случаев, когда нужен vLLM/llama.cpp напрямую (см. Future work: SELF_HOSTED).

---

## Этап 2 — Сетевой доступ, TLS, auth, rate-limit

### 2.1. Принципиальная схема

```
CLI-агент (лаптоп)                    VPS
   │  HTTPS                              Caddy/Nginx :443  (TLS terminate, Bearer-check, rate-limit)
   │── POST /v1/chat/completions ───────►│  proxy_pass 127.0.0.1:11434
   │  Authorization: Bearer <LLM_TOKEN>  │        │
   │◄────────────────────────────────────│        ▼
   │                                      Ollama :11434 (bind 127.0.0.1, NO native auth)
```

**Безопасность:** движок на `127.0.0.1`, спереди reverse proxy. Прокси терминирует TLS, проверяет
Bearer auth, делает rate-limit. Порт 11434/8080 наружу **никогда** не выставляется.

### 2.2. Подготовка домена и DNS

- A-запись: `llm.ВАШ-ДОМЕН.ru` → `<VPS-IP>`.
- Если домена нет — см. ветвь B (SSH-туннель) ниже.

### 2.3. Генерация Bearer-токена (на VPS, один раз)

```bash
LLM_TOKEN=$(openssl rand -base64 32)
echo "СОХРАНИТЕ LLM_TOKEN для CLI-агента: $LLM_TOKEN"
```

### 2.4. Вариант A1 — Caddy (РЕКОМЕНДУЕТСЯ: auto-HTTPS из коробки)

```bash
sudo apt install -y caddy
```

`/etc/caddy/Caddyfile`:
```caddyfile
llm.ВАШ-ДОМЕН.ru {
    # Bearer auth — простая проверка header'а
    @unauth not header Authorization "Bearer {$LLM_TOKEN}"
    handle @unauth {
        respond "Unauthorized" 401
    }

    # Rate-limit: потребуется модуль caddy-ratelimit (см. ниже)
    reverse_proxy 127.0.0.1:11434 {
        header_up Host {host}
        header_up X-Real-IP {remote_host}
        # Не форвардить клиентский auth в Ollama (она его всё равно не проверяет)
        header_down -Set-Cookie
    }
}
```

```bash
# Установить caddy-ratelimit (если нужен rate-limit на уровне прокси):
caddy add-package github.com/mholt/caddy-ratelimit   # при сборке из исходников
# ИЛИ использовать дефолт без rate-limit (Ollama OLLAMA_NUM_PARALLEL=1 = естественный лимит)

# Передать токен в Caddy через env (systemd override)
sudo systemctl edit caddy
```
```ini
[Service]
Environment="LLM_TOKEN=ВАШ_СОХРАНЁННЫЙ_ТОКЕН"
```
```bash
sudo systemctl restart caddy
# Caddy сам получит cert от Let's Encrypt при первом запросе к домену.
journalctl -u caddy -n 30 --no-pager
```

### 2.5. Вариант A2 — Nginx + certbot (если уже стоит Nginx)

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

`/etc/nginx/conf.d/llm.conf`:
```nginx
# Rate-limit zone: 5 запросов/сек на IP, burst 10
limit_req_zone $binary_remote_addr zone=llm:10m rate=5r/s;

server {
    listen 80;
    server_name llm.ВАШ-ДОМЕН.ru;
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl;
    server_name llm.ВАШ-ДОМЕН.ru;

    # certs подставит certbot --nginx ниже
    # ssl_certificate /etc/letsencrypt/live/llm.ВАШ-ДОМЕН.ru/fullchain.pem;
    # ssl_certificate_key /etc/letsencrypt/live/llm.ВАШ-ДОМЕН.ru/privkey.pem;

    # Bearer auth (basic — map-проверка)
    map $http_authorization $auth_ok {
        default 0;
        "Bearer ВАШ_СОХРАНЁННЫЙ_ТОКЕН" 1;
    }

    location /v1/ {
        if ($auth_ok = 0) { return 401; }
        limit_req zone=llm burst=10 nodelay;

        proxy_pass http://127.0.0.1:11434;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_buffering off;        # важно для streaming (future SSE)
        proxy_read_timeout 300s;    # локальная модель может быть медленной
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d llm.ВАШ-ДОМЕН.ru
```

> `map` на уровне `http {}` — вынести в `/etc/nginx/conf.d/llm-map.conf` или в основной
> `nginx.conf`, т.к. `map` не разрешён внутри `server`. На практике кладут map-блок в отдельный
> include до server-блоков.

### 2.6. Ветвь B — только IP, без домена (dev-only)

**B1 — SSH-туннель (проще, без TLS):**
```bash
# ЛОКАЛЬНО:
ssh -L 11434:127.0.0.1:11434 cliadmin@<VPS-IP> -N
# теперь на лаптопе доступно http://localhost:11434 — трафик идёт через SSH
```
Auth не нужен (туннель приватный). CLI: `CLI_AGENT_BASE_URL=http://localhost:11434/v1`.

**B2 — self-signed TLS (dev):** см. паттерн дня 17 (`openssl req -x509`), но для LLM-эндпоинта
SSH-туннель обычно проще.

### 2.7. Проверка прокси

```bash
# Без токена → 401
curl -s -o /dev/null -w "%{http_code}\n" https://llm.ВАШ-ДОМЕН.ru/v1/models
# 401

# С неверным токеном → 401
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer wrong" \
  https://llm.ВАШ-ДОМЕН.ru/v1/models
# 401

# С верным токеном → 200 + список моделей
curl -s -H "Authorization: Bearer $LLM_TOKEN" \
  https://llm.ВАШ-ДОМЕН.ru/v1/models | jq '.data[].id'
# "qwen25-7b-private:latest"
```

### 2.8. Нюанс auth-интеграции с CLI-агентом (КЛЮЧЕВОЙ)

Текущее поведение кода (день 25, `OpenAiCompatibleClient.kt` строки 88-90): **`Authorization:
Bearer $apiKey` шлётся ТОЛЬКО если `apiKey.isNotBlank()`**. При этом:

- `provider=ollama` → `requiresApiKey()==false` → клиент не требует ключ → **пустой ключ OK** →
  Bearer-заголовок не эмитится.
- `provider=openai-compatible` → `requiresApiKey()==true` → ключ обязателен → Bearer эмитится.

**Отсюда два сценария интеграции:**

| Сценарий | provider | apiKey | auth на прокси | Работает? |
|---|---|---|---|---|
| LAN/localhost без прокси (SSH-туннель) | `ollama` | пустой | нет | ДА — как есть |
| Ollama за Caddy/Nginx С auth-проверкой | `openai-compatible` | `$LLM_TOKEN` | Bearer-check в прокси | ДА — клиент эмитит Bearer |
| Ollama за прокси БЕЗ auth | `ollama` | пустой | нет | ДА — как есть (но публичный эндпоинт, не рекомендуется) |

> **Рекомендация для минимального стенда:** если нужен auth через прокси — переключить CLI на
> `provider=openai-compatible` + реальный `$LLM_TOKEN`. Это единственная тонкость интеграции.
> Кодовая правка (`SELF_HOSTED` provider) — только если хочется vLLM/llama.cpp на не-Ollama порту
> без auth (см. Этап 3.5 и Future work).

---

## Этап 3 — Интеграция с CLI-агентом

### 3.1. Готовность кода (GAP-анализ)

| Компонент | Статус | Где |
|---|---|---|
| Multi-provider dispatch | ✅ готово | `LlmClientFactory.kt` |
| `LlmProvider.OLLAMA` (requiresApiKey=false) | ✅ готово | `LlmProvider.kt` |
| OpenAI-compat wire-формат (`/v1/chat/completions`) | ✅ готово | `OpenAiCompatibleClient.kt` |
| Auth conditional (Bearer только при apiKey) | ✅ готово | `OpenAiCompatibleClient.kt:88-90` |
| Retry на 429/5xx (10 попыток, backoff) | ✅ готово | `OpenAiCompatibleClient.kt:54-78` |
| `ModelLimitsRegistry` prefix-match для qwen2.5/llama3.1 | ✅ готово | `ModelLimits.kt` |
| Env-override (`CLI_AGENT_*`) > config.json | ✅ готово | `ConfigRepository.kt` |
| Request timeout = 120s | ⚠️ тесно для загруженной CPU 7B | `OpenAiCompatibleClient.kt:37-41` |
| Streaming SSE | ❌ не реализован (UX gap, Phase 2) | — |
| `SELF_HOSTED` provider без auth | ❌ только если не-Ollama без auth | опционально |

### 3.2. Сценарий 1 — local Ollama через SSH-туннель (без auth)

```bash
# ЛОКАЛЬНО (лаптоп), после `ssh -L 11434:127.0.0.1:11434 cliadmin@<VPS-IP> -N`:
export CLI_AGENT_PROVIDER=ollama
export CLI_AGENT_BASE_URL=http://localhost:11434/v1
export CLI_AGENT_MODEL=qwen25-7b-private
# CLI_AGENT_API_KEY НЕ задаём (пустой — Ollama без auth)
./gradlew run
```

> Auto-detect тоже сработает: `LlmProvider.autoDetect("http://localhost:11434/v1")` → OLLAMA (по
> `:11434`). Но явный `CLI_AGENT_PROVIDER=ollama` надёжнее.

### 3.3. Сценарий 2 — local Ollama через HTTPS-прокси с auth

```bash
# ЛОКАЛЬНО:
export CLI_AGENT_PROVIDER=openai-compatible
export CLI_AGENT_BASE_URL=https://llm.ВАШ-ДОМЕН.ru/v1
export CLI_AGENT_MODEL=qwen25-7b-private
export CLI_AGENT_API_KEY=$LLM_TOKEN    # Bearer эмитится клиентом, проверяется в Caddy/Nginx
./gradlew run
```

> Почему `openai-compatible`, а не `ollama`: за прокси с Bearer-check нужен эмит `Authorization`.
> При `provider=ollama` клиент может не послать header, и прокси вернёт 401.

### 3.4. Альтернатива — `/config set` (вместо env)

Из REPL (после рестарта chat, т.к. `/config set` не hot-reload — это известное поведение
`ConfigRepository`):

```
/config set provider openai-compatible
/config set baseUrl https://llm.ВАШ-ДОМЕН.ru/v1
/config set model qwen25-7b-private
/config set apiKey ВАШ_LLM_TOKEN
# затем /restart ИЛИ выход и ./gradlew run заново
```

### 3.5. Проверка ModelLimitsRegistry

Реестр (`ModelLimits.kt`) содержит `"qwen2.5"` и `"llama3.1"` → `ModelLimits(128000, 8192)`.
Prefix-match:
```
"qwen25-7b-private".startsWith("qwen2.5")  → false (имя кастомной модели без точки!)
"qwen2.5:7b-instruct-q5_K_M".startsWith("qwen2.5") → true  → 128K/8K  ✓
```

**Внимание:** кастомное имя модели `qwen25-7b-private` (из Modelfile, без точки в `qwen2.5`) НЕ
матчится с prefix `qwen2.5` → упадёт в `DEFAULT` (128000/8192) — что в данном случае совпадает по
значению, так что фактически работает. Но для чистоты — либо называть модель с префиксом
`qwen2.5-7b-private` (тогда `startsWith("qwen2.5")` → true), либо использовать официальный тег
`qwen2.5:7b-instruct-q5_K_M` напрямую.

**Опциональная регистрация точной модели** (если хочется явности):
```kotlin
// ModelLimits.kt — добавить строку в registry:
"qwen25-7b-private" to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
```

### 3.6. Опциональные кодовые правки (ТОЛЬКО если не используется Ollama)

**Правка 1 — `SELF_HOSTED` provider для не-Ollama endpoint без auth (~15 строк).**
Если деплоится vLLM/llama.cpp на не-:11434 порту без auth, а пользователь не хочет эмитить
Bearer:
```kotlin
// LlmProvider.kt — добавить:
SELF_HOSTED("self-hosted");

// requiresApiKey(): this != OLLAMA && this != SELF_HOSTED

// fromString(): "self-hosted", "selfhosted", "local-noauth" -> SELF_HOSTED
```

**Правка 2 — поднять `requestTimeoutMillis` под медленную CPU-модель.**
Текущие 120s (`OpenAiCompatibleClient.kt:38`) могут быть тесны для загруженной CPU 7B (TTFT +
генерация 2K токенов при 10-15 tok/s ≈ 130-200s). Варианты:
- Поднять до `180_000`–`300_000` (быстро, но глобально для всех провайдеров).
- Сделать конфигурируемым: `CLI_AGENT_REQUEST_TIMEOUT_MILLIS` → читать в `OpenAiCompatibleClient`.

**Правка 3 — env-override для ModelLimits (опционально):**
```kotlin
// OutputBudget.kt или ModelLimits.kt — env CLI_AGENT_CONTEXT_WINDOW / CLI_AGENT_MAX_OUTPUT
// override для ModelLimitsRegistry.forModel(). Для 7B реальный контекст 128K (OK), max output 8K (OK).
```

> **Для минимального стенда все 3 правки НЕ обязательны** — Ollama на :11434 + auto-detect +
> пустой ключ работает как есть. Правка 2 (timeout) — самая обоснованная для UX.

---

## Этап 4 — Acceptance-чеклист Дня 30

Все команды — bash с `jq`. Выполняются с лаптопа (через домен/туннель), если явно не помечено «на VPS».

### (a) Доступ к модели через curl

```bash
DOMAIN=https://llm.ВАШ-ДОМЕН.ru
TOKEN=$LLM_TOKEN
MODEL=qwen25-7b-private

# Список моделей
curl -s -H "Authorization: Bearer $TOKEN" $DOMAIN/v1/models | jq '.data[].id'
# PASS: содержит "$MODEL"

# Чат
curl -s -H "Authorization: Bearer $TOKEN" $DOMAIN/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Назови столицу Франции одним словом.\"}],\"max_tokens\":32,\"temperature\":0}" \
  | jq '.choices[0].message.content'
# PASS: содержит "Париж" (Paris)
```

### (b) Стабильность под 8 параллельными запросами

```bash
for i in {1..8}; do
  curl -s -o /tmp/llm_$i.json -w "%{http_code} %{time_total}s\n" \
    -H "Authorization: Bearer $TOKEN" $DOMAIN/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Посчитай от 1 до $i словами.\"}],\"max_tokens\":64}" &
done
wait
echo "--- статусы ---"
ls /tmp/llm_*.json | wc -l    # PASS: 8
# Проверить, что все 200 и есть content:
for f in /tmp/llm_*.json; do jq -r '.choices[0].message.content // "EMPTY"' $f | head -c 40; echo; done
```
**PASS:** все 8 ответов вернулись с content (не пустые), ни одного 5xx. Допустимо, что часть
сериализуется через 1 слот `OLLAMA_NUM_PARALLEL=1` (последовательно) — это норма для дешёвого VPS.

### (c) Проверка rate-limit (429 под флудом)

```bash
# Флуд 50 запросов почти одновременно (Nginx rate=5r/s, burst=10)
for i in $(seq 1 50); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer $TOKEN" $DOMAIN/v1/models &
done | sort | uniq -c
# PASS: есть заметное количество 429 (rate-limit сработал). Клиент корректно их ретраит (до 10 раз).
```
**PASS:** в выводе присутствуют 429 (не только 200). Это подтверждает, что лимит работает и клиент
(`OpenAiCompatibleClient` retry на 429) сможет их пережить.

### (d) Проверка max-context (oversize prompt → reject/truncate)

```bash
# Сгенерировать промпт длиннее num_ctx=8192 (~4-5 токена на слово, берём с запасом)
BIG=$(python3 -c "print('токен ' * 20000)" 2>/dev/null || printf 'токен %.0s' {1..20000})
curl -s -H "Authorization: Bearer $TOKEN" $DOMAIN/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg m "$MODEL" --arg c "$BIG" '{model:$m, messages:[{role:"user",content:$c}], max_tokens:16}')" \
  | jq '{error: .error, finish: .choices[0].finish_reason, content_len: (.choices[0].message.content | length)}'
# PASS: либо 4xx/error от движка (context overflow), либо finish_reason="length" с коротким ответом.
# НИ в коем случае не fall-through в OOM/крах процесса ollama.
```
**PASS:** движок либо отверг, либо обрезал контекст без падения сервиса. Проверить, что ollama
жива: `systemctl is-active ollama` (на VPS) → `active`.

### (e) Интеграция с CLI-агентом

```bash
# Сценарий 2 (HTTPS + auth) — env экспортированы (Этап 3.3):
./gradlew run
```
В REPL:
```
> Привет! Ты работаешь на локальной модели?
< ... (ответ от qwen25-7b-private)
/llm
# PASS: показывает provider=openai-compatible (или ollama), model=qwen25-7b-private, baseUrl=https://llm...
/model
# PASS: qwen25-7b-private
```
**PASS:** CLI-агент отвечает через удалённую локальную модель, баннер показывает правильный
provider/model/baseUrl, `/llm` и `/model` корректны. Retry на 429 работает (видно по `[retry]` в
stderr при флуде).

### Сводка критериев pass/fail

| Тест | PASS-критерий |
|---|---|
| (a) curl /v1/models + chat | 200, модель в списке, осмысленный ответ |
| (b) 8 параллельных | 8 ответов, 0 падений сервиса, нет 5xx |
| (c) rate-limit flood | есть 429, клиент ретраит |
| (d) oversize context | reject/truncate без краха ollama |
| (e) CLI-агент | отвечает, провайдер/модель корректны |

---

## Этап 5 — Гибрид (managed API для тяжёлых задач)

### 5.1. Идея гибрида

Self-hosting 7B не покрывает reasoning/long-context задачи. Managed API (DeepSeek V3, Groq Qwen3-32B,
OpenRouter) для 1-10M токенов/мес стоит $1-15 — дешевле self-hosting GPU. Гибрид: local для
приватного/лёгкого, managed для тяжёлого.

### 5.2. Управление профилями через env

CLI-агент читает `CLI_AGENT_*` env при старте. Два профиля — два набора env (через `direnv` /
`.envrc` / скрипт-обёртку):

**Профиль LOCAL (приватный):**
```bash
# ~/profiles/cli-agent-local.sh
export CLI_AGENT_PROVIDER=openai-compatible
export CLI_AGENT_BASE_URL=https://llm.ВАШ-ДОМЕН.ru/v1
export CLI_AGENT_MODEL=qwen25-7b-private
export CLI_AGENT_API_KEY=$LLM_TOKEN
```

**Профиль DEEPSEEK (reasoning/long-context):**
```bash
# ~/profiles/cli-agent-deepseek.sh
export CLI_AGENT_PROVIDER=openai-compatible
export CLI_AGENT_BASE_URL=https://api.deepseek.com/v1
export CLI_AGENT_MODEL=deepseek-chat           # или deepseek-reasoner для reasoning
export CLI_AGENT_API_KEY=$DEEPSEEK_API_KEY
```

**Профиль GROQ (быстрый, Qwen3-32B):**
```bash
# ~/profiles/cli-agent-groq.sh
export CLI_AGENT_PROVIDER=openai-compatible
export CLI_AGENT_BASE_URL=https://api.groq.com/openai/v1
export CLI_AGENT_MODEL=qwen/qwen3-32b
export CLI_AGENT_API_KEY=$GROQ_API_KEY
```

```bash
# Использование:
source ~/profiles/cli-agent-local.sh     && ./gradlew run   # приватные задачи
source ~/profiles/cli-agent-deepseek.sh  && ./gradlew run   # reasoning / длинный контекст
```

### 5.3. Когда какой профиль

| Задача | Профиль | Почему |
|---|---|---|
| Приватные данные (личные заметки, код под NDA) | LOCAL (7B) | данные не покидают VPS |
| Короткий чат, простые вопросы | LOCAL (7B) | 10-15 tok/s достаточно, бесплатно |
| RAG по большим корпусам (длинный контекст) | DEEPSEEK / GROQ | 128K контекст, 32B reasoning |
| Сложный код/reasoning | DEEPSEEK reasoner | качество 32B+ |
| Быстрый инференс (low latency) | GROQ | десятки tok/s на GPU |

### 5.4. Бюджет

| Статья | Поставщик | Стоимость/мес |
|---|---|---|
| VPS (CX32, 8 GB) | Hetzner | ~€6.5 (~$7) |
| Managed API (1-10M токенов) | DeepSeek/Groq | ~$1-3 |
| **Итого** | | **~$8-10/мес** |

> Self-host 32B GPU = $250-500/мес — окупается только при >150-800M токенов/мес ИЛИ при жёстком
> требовании приватности 100% трафика. Для учебного стенда гибрид оптимален.

---

## Риски и что НЕ делать

### Anti-patterns

| Антипаттерн | Почему плохо | Правильно |
|---|---|---|
| **EXL2/GPTQ/AWQ на CPU** | GPU-only форматы, на CPU либо не запустятся, либо 0.5 tok/s | GGUF (Q4_K_M/Q5_K_M) |
| **Q2/Q3 квантование** | Качество падает катастрофически (галлюцинации, потеря инструкций) | Q4_K_M минимум, Q5_K_M если RAM позволяет |
| **Большой `n_ctx` по умолчанию** (32K-128K) | KV-cache растёт линейно → OOM на 8 GB VPS, throughput падает | cap `num_ctx 4096-8192`, поднимать только при необходимости |
| **vLLM на CPU** | FP32-only на CPU, медленнее llama.cpp; AVX-оптимизации ограничены | llama.cpp / Ollama для CPU |
| **Прямой порт 11434/8080 наружу** | Ollama/llama-server без нативного auth → публичный бесплатный endpoint | bind 127.0.0.1 + reverse proxy с auth |
| **`OLLAMA_ORIGINS=*`** | Открывает CORS для всех → abuse через браузер | перечислить конкретные origin |
| **32B на CX32 (8 GB)** | 1.5-3 tok/s (НЕюзабельно для чата), OOM | 7-8B Q4/Q5 для single-user чата |
| **Своп вместо `--mmap`** | Изнашивает NVMe, медленнее mmap | `--mmap` (default), не отключать |
| **Парольный SSH** | brute-force | ключи + `PasswordAuthentication no` + fail2ban |
| **Open reverse-proxy без auth** для публичного домена | любой может пользоваться вашим GPU/VPS | Bearer-check в Caddy/Nginx |
| **Игнорировать cold-start** | первый запрос после idle → 10-30с пустоты | `OLLAMA_KEEP_ALIVE=30m` (или `-1` для always-on) |

### Риски стенда

- **Noisy neighbour на Contabo** — VPS oversold, реальная скорость ниже заявленной. Митигация: Hetzner.
- **OOM при большом контексте** — `OLLAMA_NUM_PARALLEL=1` + `num_ctx 8192` = главный
  предохранитель. Мониторинг RAM: `free -m`, `ollama ps`.
- **Таймаут 120s в клиенте** — при загруженной CPU 7B + длинный промпт TTFT может упереться в
  лимит. Митигация: правка 2 (поднять до 180-300s) ИЛИ меньший `num_ctx`.
- **UX-провал без streaming** — пользователь ждёт 10-30с «пустоты» до первого токена. Это biggest
  UX gap (см. Future work).

---

## Future work (не блокирует День 30)

| Фича | Обоснование | Оценка |
|---|---|---|
| **Streaming SSE** (`stream:true` в `ChatRequest`) | Biggest UX gap: пользователь видит токены по мере генерации вместо 10-30с пустоты. Ollama/llama-server оба поддерживают SSE. | Phase 2, средне |
| **`SELF_HOSTED` provider** (requiresApiKey=false) | Для vLLM/llama.cpp на не-:11434 без auth. ~15 строк в `LlmProvider.kt`. Сейчас обходимся через `provider=ollama` (auto-detect) или `openai-compatible`+key. | мало |
| **Конфигурируемый `requestTimeoutMillis`** (`CLI_AGENT_REQUEST_TIMEOUT_MILLIS`) | CPU 7B может не укладываться в 120s. env-override > хардкод. | мало |
| **env-override для `ModelLimits`** (`CLI_AGENT_CONTEXT_WINDOW` / `CLI_AGENT_MAX_OUTPUT`) | Точная настройка лимитов без правки реестра. | мало |
| **Pre-ping / warmup** при старте CLI | Избежать cold-start на первом запросе (probe `/v1/models` до первого чата). | мало |
| **Мониторинг** (Prometheus + Grafana, node_exporter + ollama metrics) | RAM/CPU/throughput/tok-s. OOM-alerting. | средне |
| **Pre-fetch/ kv-cache reusing** | Ollama `OLLAMA_NUM_PARALLEL>1` + cache reuse для повторяющихся системных промптов. | средне |
| **Multi-model** (Qwen для чата + embedding-модель для RAG) | RAG на том же VPS через `ollama pull nomic-embed-text`. | средне |

---

## Ссылки на источники

### Inference-движки
- Ollama — официальный сайт и install: https://ollama.com , https://github.com/ollama/ollama
- Ollama OpenAI-compat API: https://github.com/ollama/ollama/blob/main/docs/openai.md
- llama.cpp: https://github.com/ggerganov/llama.cpp
- llama-server flags reference: https://github.com/ggerganov/llama.cpp/tree/master/tools/server
- vLLM (для GPU-сценариев): https://docs.vllm.ai

### Модели (GGUF)
- Qwen2.5-7B-Instruct GGUF: https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF
- Llama 3.1 8B GGUF: https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF
- GGUF quantization overview (Q4_K_M и др.): https://github.com/ggerganov/llama.cpp/blob/master/examples/quantize/README.md

### VPS и цены
- Hetzner Cloud pricing: https://www.hetzner.com/cloud (CX22 / CX32 / CX42)
- Hetzner Server Auction (dedicated): https://www.hetzner.com/sb
- Contabo VPS: https://contabo.com/en/vps/
- Vultr: https://www.vultr.com/pricing/

### Сеть/TLS/proxy
- Caddy: https://caddyserver.com/docs/
- caddy-ratelimit: https://github.com/mholt/caddy-ratelimit
- Nginx limit_req: https://nginx.org/en/docs/http/ngx_http_limit_req_module.html
- Certbot: https://certbot.eff.org/

### Managed API (гибрид)
- DeepSeek pricing: https://api-docs.deepseek.com/quick_start/pricing
- Groq pricing: https://groq.com/pricing/
- OpenRouter: https://openrouter.ai/models

### Проект-референсы (CLI-агент, дни 17/25)
- День 17 MCP-VPS деплой (шаблон systemd/nginx/ufw): `plan/finisheddays/day-17-vps/vps-deploy.md`
- День 25 multi-provider (LlmProvider/LlmClientFactory): `src/main/kotlin/com/cliagent/llm/`
- Конвенции проекта: `AGENTS.md`
