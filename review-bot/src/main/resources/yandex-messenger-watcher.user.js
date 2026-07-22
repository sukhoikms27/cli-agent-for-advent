// ==UserScript==
// @name         Yandex Messenger — Review Bot Watcher
// @namespace    https://github.com/sukhoikms27/cli-agent
// @version      0.1.0
// @description  Перехватывает новые сообщения о заданиях в Яндекс Мессенджере и POST'ит на локальный review-bot webhook. DOM-наблюдение (работает с бинарным WS — клиент сам декодирует, мы ловим отрендеренный текст).
// @match        https://*.yandex-team.ru/*
// @match        https://yandex.ru/messenger/*
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @connect      localhost
// @connect      127.0.0.1
// ==/UserScript==

(function () {
    'use strict';

    // ────────────────────────────────────────────────────────────────────────────
    // Конфигурация. Если review-bot слушает на другом порту — поменяй WEBHOOK_URL.
    // ────────────────────────────────────────────────────────────────────────────
    const WEBHOOK_URL = 'http://localhost:8082/messenger-event';

    // Логирование в консоль браузера (F12 → Console). true = подробно для дебага.
    const DEBUG = true;

    function log(...args) {
        if (DEBUG) console.log('[review-bot]', ...args);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Парсер одного DOM-узла сообщения.
    //
    // Структура (см. образец в day35-plan-v2.md §6.4):
    //   <div class="yamb-message-content" data-timestamp="...">
    //     <div class="yamb-message-text">
    //       <span class="text" data-copyable="true">
    //         <p>
    //           <a class="link link_md" href="https://st.yandex-team.ru/PCR-1989840">Новое задание</a>: [5] Искандар Хамитов (<a class="link" href="mailto:...">...</a>)
    //         </p>
    //       </span>
    //       ...
    //     </div>
    //   </div>
    //
    // Извлекаем:
    //   - trackerUrl из <a class="link link_md" href="https://st.yandex-team.ru/PCR-XXXX">
    //   - sprint из регулярки \[(\d+)\]
    //   - studentName из регулярки [N] <ФИО> (до скобок с email)
    // ────────────────────────────────────────────────────────────────────────────
    function parseMessage(node) {
        if (!node || node.nodeType !== Node.ELEMENT_NODE) return null;

        // Берём только сообщения, содержащие «Новое задание» — иначе миллион фолс-позитивов.
        const text = node.textContent || '';
        if (!text.includes('Новое задание')) return null;

        // Tracker link: первый <a class="link link_md"> со ссылкой на st.yandex-team.ru.
        const trackerLink = node.querySelector('a.link_md[href*="st.yandex-team.ru"]');
        const trackerUrl = trackerLink ? trackerLink.getAttribute('href') : null;
        if (!trackerUrl) {
            log('Новое задание без tracker-link — пропускаю', text.slice(0, 80));
            return null;
        }

        // Спринт: [N]
        const sprintMatch = text.match(/\[(\d+)\]/);
        const sprint = sprintMatch ? parseInt(sprintMatch[1], 10) : null;

        // Студент: всё между [N] и ( — это ФИО.
        // Текст узла: «Новое задание: [5] Искандар Хамитов (hamitoffiskandar@yandex.ru)»
        const studentMatch = text.match(/\[\d+\]\s+(.+?)\s*\(/);
        const studentName = studentMatch ? studentMatch[1].trim() : null;

        // message-id из data-timestamp — для дедупликации на стороне review-bot.
        const messageId = node.getAttribute('data-timestamp') || null;

        return {
            source: 'dom',
            messageId,
            trackerUrl,
            sprint,
            studentName,
            rawText: text.trim().slice(0, 500),
            ts: Date.now(),
        };
    }

    // ────────────────────────────────────────────────────────────────────────────
    // POST на review-bot webhook. Используем GM_xmlhttpRequest, а не fetch, т.к.
    // корпоративная страница может слать CORS-запрос на localhost — GM_xmlhttpRequest
    // обходит same-origin policy (это явная привилегия Tampermonkey).
    // ────────────────────────────────────────────────────────────────────────────
    function forward(payload) {
        const body = JSON.stringify(payload);
        log('forward →', WEBHOOK_URL, body);
        GM_xmlhttpRequest({
            method: 'POST',
            url: WEBHOOK_URL,
            headers: { 'Content-Type': 'application/json' },
            data: body,
            onload: (resp) => log('review-bot accepted:', resp.status, resp.responseText),
            onerror: (err) => log('review-bot недоступен. Запущен ли `review-bot watch`?', err),
            ontimeout: () => log('review-bot timeout'),
        });
    }

    // ────────────────────────────────────────────────────────────────────────────
    // MutationObserver на контейнер чата. SPA — контейнер может появиться позже
    // загрузки страницы, поэтому retry'имся каждые 1с до успеха.
    // ────────────────────────────────────────────────────────────────────────────
    function attachObserver() {
        // Слушаем добавление любых сообщений. Селектор широкий (по классу), но
        // parseMessage() фильтрует по «Новое задание» — пустые/нецелевые сообщения
        // игнорируются.
        const observer = new MutationObserver((mutations) => {
            for (const mutation of mutations) {
                for (const node of mutation.addedNodes) {
                    if (node.nodeType !== Node.ELEMENT_NODE) continue;
                    // node сам может быть сообщением или содержать сообщения внутри.
                    const candidates = node.matches && node.matches('.yamb-message-content')
                        ? [node]
                        : Array.from(node.querySelectorAll ? node.querySelectorAll('.yamb-message-content') : []);
                    for (const candidate of candidates) {
                        const parsed = parseMessage(candidate);
                        if (parsed) forward(parsed);
                    }
                }
            }
        });

        const tryAttach = (attempt = 0) => {
            // Контейнер сообщений у Яндекс.Мессенджера — пробуем несколько селекторов.
            const root =
                document.querySelector('[class*="messages"]') ||
                document.querySelector('[class*="chat-body"]') ||
                document.querySelector('main') ||
                document.body;
            if (root) {
                observer.observe(root, { childList: true, subtree: true });
                log('наблюдатель установлен на', root.tagName + '.' + (root.className || ''), '(attempt', attempt + ')');
            } else if (attempt < 30) {
                setTimeout(() => tryAttach(attempt + 1), 1000);
            } else {
                log('не нашёл контейнер чата за 30 попыток — messages не будут перехвачены');
            }
        };
        tryAttach();
    }

    attachObserver();
    log('userscript загружен. webhook =', WEBHOOK_URL);
})();
