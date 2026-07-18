// День 30: клиент веб-агента «Мотиватор». Vanilla JS, без сборки.
// Адаптивный: одна кодовая база для десктопа и мобайла (CSS media queries в style.css).
//
// API контракт с MotivatorApp.kt:
//   POST /api/session          → {sessionId} + Set-Cookie: sid=...
//   GET  /api/history          → {messages: [{role, content}]}
//   POST /api/chat {message, temperature}
//        → text/event-stream: события start / token {token} / done {full} / error {msg}

(() => {
  'use strict';

  const chatWindow = document.getElementById('chat-window');
  const messageInput = document.getElementById('message-input');
  const sendButton = document.getElementById('send-button');
  const temperatureSlider = document.getElementById('temperature');
  const temperatureValue = document.getElementById('temperature-value');
  const statusIndicator = document.getElementById('status');

  let sending = false;

  // ── Температура: слайдер → отображение ──
  temperatureSlider.addEventListener('input', () => {
    temperatureValue.textContent = Number(temperatureSlider.value).toFixed(2);
  });

  // ── Авто-resize textarea (растёт с контентом, до max-height) ──
  messageInput.addEventListener('input', () => {
    messageInput.style.height = 'auto';
    messageInput.style.height = `${Math.min(messageInput.scrollHeight, 140)}px`;
  });

  // ── Отправка по Enter (без Shift) ──
  messageInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  sendButton.addEventListener('click', sendMessage);

  // ── Базовый рендер пузыря сообщения ──
  function addBubble(role, text) {
    hideEmptyState();
    const bubble = document.createElement('div');
    bubble.className = `message message--${role}`;
    const content = document.createElement('div');
    content.className = 'message__content';
    content.textContent = text;
    bubble.appendChild(content);
    chatWindow.appendChild(bubble);
    chatWindow.scrollTop = chatWindow.scrollHeight;
    return content;
  }

  function hideEmptyState() {
    const empty = document.getElementById('empty-state');
    if (empty) empty.remove();
  }

  function setStatus(text, isWorking) {
    statusIndicator.textContent = text;
    statusIndicator.classList.toggle('status--working', !!isWorking);
  }

  // ── Загрузка истории при старте (изоляция: только своя сессия по cookie) ──
  async function loadHistory() {
    try {
      const resp = await fetch('/api/history', { credentials: 'same-origin' });
      if (resp.status === 401) {
        setStatus('Сессия не создана', false);
        return;
      }
      const data = await resp.json();
      data.messages.forEach((m) => addBubble(m.role, m.content));
      setStatus('Готов', false);
    } catch (e) {
      setStatus('Ошибка загрузки истории', false);
    }
  }

  // ── Основной цикл: отправить сообщение, стримить ответ ──
  async function sendMessage() {
    if (sending) return;
    const message = messageInput.value.trim();
    if (!message) return;

    sending = true;
    sendButton.disabled = true;
    sendButton.textContent = '...';
    messageInput.value = '';

    addBubble('user', message);
    const assistantContent = addBubble('assistant', '');
    assistantContent.classList.add('message__content--typing');
    setStatus('Мотиватор печатает…', true);

    const temperature = Number(temperatureSlider.value);

    try {
      const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ message, temperature }),
      });

      if (resp.status === 401) {
        assistantContent.textContent = 'Сессия истекла. Перезагрузите страницу.';
        setStatus('Требуется сессия', false);
        return;
      }
      if (!resp.ok) {
        const err = await resp.json().catch(() => ({ error: resp.statusText }));
        assistantContent.textContent = `Ошибка: ${err.error}`;
        setStatus('Ошибка', false);
        return;
      }

      // Ручной парсинг SSE-потока (fetch + ReadableStream; EventSource не умеет POST).
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let full = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const frames = buffer.split('\n\n');
        buffer = frames.pop() ?? '';
        for (const frame of frames) {
          const evt = parseSseFrame(frame);
          if (!evt) continue;
          if (evt.event === 'token') {
            full += evt.data.token;
            assistantContent.textContent = full;
            chatWindow.scrollTop = chatWindow.scrollHeight;
          } else if (evt.event === 'done') {
            full = evt.data.full || full;
            assistantContent.textContent = full;
          } else if (evt.event === 'error') {
            assistantContent.textContent = `Ошибка: ${typeof evt.data === 'string' ? evt.data : evt.data}`;
          }
        }
      }
      assistantContent.textContent = full || '(пустой ответ)';
      setStatus('Готов', false);
    } catch (e) {
      assistantContent.textContent = `Сетевая ошибка: ${e.message}`;
      setStatus('Сетевая ошибка', false);
    } finally {
      assistantContent.classList.remove('message__content--typing');
      sending = false;
      sendButton.disabled = false;
      sendButton.textContent = 'Отправить';
      messageInput.focus();
    }
  }

  function parseSseFrame(frame) {
    let event = 'message';
    const dataLines = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
    }
    if (dataLines.length === 0) return null;
    const raw = dataLines.join('\n');
    try {
      return { event, data: JSON.parse(raw) };
    } catch {
      return { event, data: raw };
    }
  }

  // ── Старт: гарантируем анонимную сессию, грузим историю ──
  (async () => {
    // POST /api/session создаёт cookie, если его ещё нет. Безопасно повторять.
    try {
      await fetch('/api/session', { method: 'POST', credentials: 'same-origin' });
    } catch {
      // не критично — сервер мог уже поставить cookie
    }
    await loadHistory();
    messageInput.focus();
  })();
})();
