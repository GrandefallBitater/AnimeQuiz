$(document).ready(function () {
    const csrfToken  = $('meta[name="_csrf"]').attr('content');
    const csrfHeader = $('meta[name="_csrf_header"]').attr('content');
    if (csrfToken && csrfHeader) {
        const headers = {};
        headers[csrfHeader] = csrfToken;
        $.ajaxSetup({ headers: headers });
    }

    const PAGE_SIZE = 60;
    const FALLBACK_POSTER = '/server-images/anime-logo.png';

    const STATUS_NAMES = {
        watching:   'Смотрю',
        rewatching: 'Пересматриваю',
        completed:  'Просмотрено',
        planned:    'В планах',
        on_hold:    'Отложено',
        dropped:    'Брошено'
    };
    const STATUS_ORDER = ['watching','rewatching','planned','completed','on_hold','dropped'];
    const ACTIVE_STATUSES = [];

    let allTitles    = [];
    let filtered     = [];
    let currentPage  = 1;
    let searchQuery  = '';
    let sortMode     = 'default';
    let onlyLocal    = true;
    let viewMode     = 'grid';

    const $grid        = $('#anGrid');
    const $empty       = $('#anEmpty');
    const $loadMore    = $('#anLoadMore');
    const $search      = $('#anSearch');
    const $searchClear = $('#anSearchClear');
    const $sort        = $('#anSort');
    const $onlyLocal   = $('#anOnlyLocal');
    const $viewBtns    = $('.an-view-btn');
    const $statusStats = $('#anStatusStats');

    const $statTotal    = $('#anStatTotal');
    const $statShown    = $('#anStatShown');
    const $statLocal    = $('#anStatLocal');
    const $statSource   = $('#anStatSource');
    const $statImported = $('#anStatImported');

    const $titlesPanel  = $('#titlesPanel');
    const $titlesEmpty  = $('#titlesEmpty');
    const $lastImports  = $('.js-last-import');
    const $summaryCount = $('#summaryTitlesCount');

    function ingestViews(data) {
        const titles = (data.titles || []).map((t, i) => Object.assign({}, t, { _idx: i }));
        allTitles = titles;

        const hidden = data.hiddenCount || 0;
        const total  = data.totalCount || titles.length;

        $statTotal.text(total);
        $statLocal.text(titles.filter(hasPoster).length);
        $statSource.text(data.source || 'shikimori');
        $statImported.text(formatImportedAt(data.importedAt));
        $lastImports.text(formatImportedAt(data.importedAt));
        $summaryCount.text(total);

        if (hidden > 0) {
            $('#anStatHidden').text('+' + hidden + ' в обработке').show();
        } else {
            $('#anStatHidden').hide();
        }

        renderStatusCounters();
        applyFilters();

        $titlesPanel.removeClass('hidden');
        $titlesEmpty.addClass('hidden');
    }

    function revealOnScroll() {
        const windowHeight = window.innerHeight;
        const elementVisible = 100;

        $('.reveal-on-scroll').each(function () {
            const elementTop = this.getBoundingClientRect().top;
            if (elementTop < windowHeight - elementVisible) {
                $(this).addClass('visible');
            }
        });
    }

    function renderStatusCounters() {
        const counts = {};
        allTitles.forEach(t => {
            const s = t.status || '';
            counts[s] = (counts[s] || 0) + 1;
        });

        const allActive = ACTIVE_STATUSES.length === 0;

        let html = `
            <button type="button" class="an-status-pill ${allActive ? 'is-active' : ''}"
                    data-status="">Все <b>${allTitles.length}</b></button>`;

        STATUS_ORDER.forEach(key => {
            const count = counts[key] || 0;
            if (count === 0) return;
            const active = ACTIVE_STATUSES.includes(key) ? 'is-active' : '';
            html += `<button type="button" class="an-status-pill ${active}" data-status="${key}">
                        ${STATUS_NAMES[key]} <b>${count}</b>
                     </button>`;
        });

        $statusStats.html(html);
    }

    function applyFilters() {
        const q = searchQuery.trim().toLowerCase();

        filtered = allTitles.filter(t => {
            if (ACTIVE_STATUSES.length && !ACTIVE_STATUSES.includes(t.status || '')) return false;
            if (onlyLocal && !hasPoster(t)) return false;
            if (!q) return true;

            const title   = (t.title   || '').toLowerCase();
            const name    = (t.name    || '').toLowerCase();
            const russian = (t.russian || '').toLowerCase();
            return title.includes(q) || name.includes(q) || russian.includes(q);
        });

        sortList();
        currentPage = 1;
        render();
    }

    function hasPoster(t) {
        if (t.posterHash && t.posterExt) return true;
        if (t.posterStatus === 'no_image') return false;
        return false;
    }

    const collator = new Intl.Collator('ru', { sensitivity: 'base' });

    function startsWithLetter(s) {
        if (!s) return false;
        const first = [...s][0];
        return /\p{L}/u.test(first);
    }

    function sortList() {
        switch (sortMode) {
            case 'title-asc':
            case 'title-desc': {
                const dir = sortMode === 'title-asc' ? 1 : -1;
                filtered.sort((a, b) => {
                    const ka = titleOf(a), kb = titleOf(b);
                    const la = startsWithLetter(ka), lb = startsWithLetter(kb);
                    if (la !== lb) return la ? -1 : 1;
                    return dir * collator.compare(ka, kb);
                });
                break;
            }
            case 'score-desc': filtered.sort((a, b) => (b.score | 0) - (a.score | 0)); break;
            case 'score-asc':  filtered.sort((a, b) => (a.score | 0) - (b.score | 0)); break;
            case 'status':
                filtered.sort((a, b) => {
                    const ia = STATUS_ORDER.indexOf(a.status);
                    const ib = STATUS_ORDER.indexOf(b.status);
                    const sa = ia === -1 ? 99 : ia;
                    const sb = ib === -1 ? 99 : ib;
                    return sa - sb || collator.compare(titleOf(a), titleOf(b));
                });
                break;
            default:
                filtered.sort((a, b) => (a._idx ?? 0) - (b._idx ?? 0));
        }
    }

    function titleOf(t) {
        const ru = (t.russian || '').trim();
        if (ru) return ru;
        return (t.title || t.name || 'Без названия').trim();
    }

    function posterOf(t) {
        const ext = String(t.posterExt || '').toLowerCase();
        if (t.posterHash && /^(png|jpe?g|gif|webp)$/.test(ext)) {
            return '/media/posters/' + encodeURIComponent(t.posterHash) + '.' + ext;
        }
        return FALLBACK_POSTER;
    }

    function render() {
        if (!filtered.length) {
            $grid.empty();
            $empty.show();
            $titlesEmpty.addClass('hidden');
            $loadMore.hide();
            $statShown.text(0);
            return;
        }
        $empty.hide();
        const shown = filtered.slice(0, currentPage * PAGE_SIZE);
        $statShown.text(shown.length);
        $grid.html(shown.map(cardHtml).join(''));
        $grid.toggleClass('list-view', viewMode === 'list');
        $grid.find('img').on('load', function () { $(this).addClass('loaded'); });
        $loadMore.toggle(shown.length < filtered.length);
    }

    function cardHtml(t) {
        const title  = escapeHtml(titleOf(t));
        const poster = escapeAttr(posterOf(t));
        const url    = escapeAttr(safeHttpUrl(stripYummyId(t.url)));
        const score  = t.score | 0;
        const status = STATUS_NAMES[t.status] ? t.status : '';
        const kind   = escapeHtml((t.kind || '').toUpperCase());
        const total  = t.totalEpisodes | 0;
        const watched = t.episodesWatched | 0;

        const statusLabel = STATUS_NAMES[status] || '';
        const statusClass = status ? 'an-status-' + status : '';

        const scoreHtml  = score > 0 ? `<span class="an-card-score">★ ${score}</span>` : '';
        const statusHtml = statusLabel ? `<span class="an-card-status ${statusClass}">${statusLabel}</span>` : '';
        const kindHtml   = kind ? `<span class="an-card-kind">${kind}</span>` : '<span></span>';
        const epsHtml    = total > 0 ? `<span class="an-card-eps">${watched} / ${total}</span>` : '';

        return `
            <a class="an-card" href="${url}" target="_blank" rel="noopener noreferrer" title="${title}">
                <div class="an-card-poster">
                    <img src="${poster}" alt="${title}" loading="lazy"
                         onerror="this.onerror=null;this.src='${FALLBACK_POSTER}';this.classList.add('loaded');">
                    ${scoreHtml}${statusHtml}
                </div>
                <div class="an-card-info">
                    <div class="an-card-title">${title}</div>
                    <div class="an-card-meta">${kindHtml}${epsHtml}</div>
                </div>
            </a>`;
    }

    function stripYummyId(value) {
        const raw = (value == null ? '' : String(value)).trim();
        if (!raw) return raw;

        try {
            const parsed = new URL(raw, window.location.origin);
            if (!/^(www\.)?yummyani\.me$/i.test(parsed.hostname)) return raw;

            const match = parsed.pathname.match(/^(\/catalog\/item\/.+)-\d+\/?$/);
            if (match) {
                parsed.pathname = match[1];
                return parsed.href;
            }
            return raw;
        } catch (e) {
            return raw;
        }
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
        }[c]));
    }
    function escapeAttr(s) { return escapeHtml(s); }

    function safeHttpUrl(value) {
        const raw = (value == null ? '' : String(value)).trim();
        if (!raw) return '#';
        try {
            const parsed = new URL(raw, window.location.origin);
            if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
                return parsed.href;
            }
        } catch (e) {
        }
        return '#';
    }

    function formatImportedAt(iso) {
        if (!iso) return '—';
        try {
            const d = new Date(iso);
            if (isNaN(d.getTime())) return iso;
            const pad = n => String(n).padStart(2, '0');
            return `${pad(d.getDate())}.${pad(d.getMonth()+1)}.${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
        } catch (e) { return iso; }
    }

    let _etaLastReady = null;
    let _etaLastTime = null;
    let _etaSamples = [];

    function formatEta(seconds) {
        if (seconds == null || !isFinite(seconds) || seconds <= 0) return '';
        if (seconds < 60) return '~' + Math.max(1, Math.round(seconds)) + ' сек';
        if (seconds < 3600) return '~' + Math.round(seconds / 60) + ' мин';
        const h = Math.floor(seconds / 3600);
        const m = Math.round((seconds % 3600) / 60);
        return '~' + h + ' ч ' + m + ' мин';
    }

    function estimateEta(ready) {
        const now = Date.now();
        if (_etaLastReady !== null && _etaLastTime !== null && ready > _etaLastReady) {
            const dReady = ready - _etaLastReady;
            const dSec = (now - _etaLastTime) / 1000;
            if (dSec > 0) {
                _etaSamples.push(dReady / dSec);
                if (_etaSamples.length > 6) _etaSamples.shift();
            }
        }
        _etaLastReady = ready;
        _etaLastTime = now;

        if (_etaSamples.length === 0) return null;
        const avg = _etaSamples.reduce((a, b) => a + b, 0) / _etaSamples.length;
        if (avg <= 0) return null;
        return avg;
    }

    function showGlobalProgress(ready, total, hidden) {
        const $prog = $('#globalImportProgress');
        if (!$prog.length) return;

        const pct = total > 0 ? Math.round(ready / total * 100) : 0;
        $prog.show();
        $prog.find('.import-progress-bar')
            .removeClass('indeterminate')
            .css('width', pct + '%');

        const rate = estimateEta(ready);
        const remaining = total - ready;
        const etaSec = rate != null && rate > 0 ? remaining / rate : null;

        let text = 'Постеры: ' + ready + ' / ' + total + ' (' + pct + '%)';
        if (hidden > 0) text += ' · ' + hidden + ' в обработке';
        if (etaSec != null) text += ' · осталось ' + formatEta(etaSec);

        $prog.find('.import-progress-text').text(text);
    }

    function resetEta() {
        _etaLastReady = null;
        _etaLastTime = null;
        _etaSamples = [];
    }

    function startPosterPolling() {
        resetEta();
        let attempts = 0;
        const timer = setInterval(function () {
            attempts++;
            $.get('/profile/import/status', function (s) {
                if (s.running) {
                    showImportRunning();
                    return;
                }
                if (s.total > 0) {
                    showGlobalProgress(s.ready, s.total, s.hidden);
                }
                if (s.done || attempts > 120) {
                    clearInterval(timer);
                    if (s.done) {
                        $('#globalImportProgress').fadeOut(500);
                        showToast('Постеры загружены', 'success');
                        $.get('/profile/titles', function (data) {
                            if (Array.isArray(data.titles)) ingestViews(data);
                        });
                    } else {
                        $('#globalImportProgress').fadeOut(500);
                        showToast('Прогрев постеров продолжается в фоне', 'info');
                    }
                }
            }).fail(function () {
            });
        }, 3000);
    }

    function showImportRunning() {
        const $prog = $('#globalImportProgress');
        if (!$prog.length) return;
        $prog.show();
        $prog.find('.import-progress-bar')
            .addClass('indeterminate')
            .css('width', '');
        $prog.find('.import-progress-text')
            .text('Сохранение списка...');
    }

    function activateTab(target) {
        $('.import-tab').removeClass('active');
        $('.import-tab[data-target="' + target + '"]').addClass('active');
        $('.import-tab-pane').removeClass('active');
        $('#pane-' + target).addClass('active');
    }

    function sourceToTab(source) {
        if (source === 'myanimelist') return 'mal';
        if (source === 'yummy') return 'yummy';
        return 'shikimori';
    }

    function bindEvents() {

        let searchTimer = null;
        $search.on('input', function () {
            const val = $(this).val();
            $searchClear.toggle(val.length > 0);
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => { searchQuery = val; applyFilters(); }, 180);
        });
        $searchClear.on('click', function () {
            $search.val('').trigger('input').trigger('focus');
        });

        $sort.on('change', function () {
            sortMode = $(this).val();
            sortList();
            currentPage = 1;
            render();
        });

        $onlyLocal.on('change', function () {
            onlyLocal = this.checked;
            applyFilters();
        });

        $viewBtns.on('click', function () {
            viewMode = String($(this).data('view'));
            $viewBtns.removeClass('is-active');
            $(this).addClass('is-active');
            $grid.toggleClass('list-view', viewMode === 'list');
        });

        $loadMore.on('click', function () { currentPage++; render(); });

        $statusStats.on('click', '.an-status-pill', function () {
            const newStatus = String($(this).data('status') || '');

            if (!newStatus) {
                ACTIVE_STATUSES.length = 0;
            } else {
                const i = ACTIVE_STATUSES.indexOf(newStatus);
                if (i === -1) ACTIVE_STATUSES.push(newStatus);
                else ACTIVE_STATUSES.splice(i, 1);
            }

            renderStatusCounters();
            applyFilters();
        });

        $('#nameForm').on('submit', function (e) {
            e.preventDefault();
            const newName = $('#userName').val().trim();
            if (!newName) { showToast('Имя не может быть пустым', 'error'); return; }

            const $btn = $('#saveNameBtn');
            const old = $btn.text();
            $btn.prop('disabled', true).text('Сохранение...');

            $.ajax({
                url: '/setCustomName',
                type: 'POST',
                data: { userName: newName },
                dataType: 'json'
            })
                .done(function (resp) {
                    showToast(resp.message || 'Имя обновлено', 'success');
                    $('#profileDisplayName').text(newName);
                    $('#heroTitle').text(newName);
                })
                .fail(function (xhr) {
                    let msg = 'Ошибка запроса';
                    try { msg = JSON.parse(xhr.responseText).message || msg; } catch (_) {}
                    showToast(msg, 'error');
                })
                .always(function () { $btn.prop('disabled', false).text(old); });
        });

        $('#avatarForm').on('submit', function (e) {
            e.preventDefault();
            const input = document.getElementById('avatarFile');
            const fileError = avatarFileError(input.files && input.files[0]);
            if (fileError) { showToast(fileError, 'error'); return; }

            const fd = new FormData();
            fd.append('avatar', input.files[0]);

            const $btn = $('#saveAvatarBtn');
            const old = $btn.text();
            $btn.prop('disabled', true).text('Загрузка...');

            $.ajax({
                url: '/profile/avatar/json',
                type: 'POST',
                data: fd,
                processData: false,
                contentType: false,
                dataType: 'json'
            })
                .done(function (resp) {
                    showToast(resp.message || 'Аватар обновлён', 'success');
                    $('#profileAvatar').attr('src', '/profile/avatar?t=' + Date.now());
                    input.value = '';
                })
                .fail(function (xhr) {
                    let msg = 'Ошибка загрузки';
                    try { msg = JSON.parse(xhr.responseText).message || msg; } catch (_) {}
                    showToast(msg, 'error');
                })
                .always(function () { $btn.prop('disabled', false).text(old); });
        });

        $('.js-import-form').on('submit', function (e) {
            e.preventDefault();
            const $form    = $(this);
            const source   = $form.data('source') || 'shikimori';
            const nickname = $form.find('.js-nickname').val().trim();
            if (!nickname) { showToast('Введите никнейм', 'error'); return; }

            const $btn   = $form.find('.js-import-btn');
            const $label = $btn.find('.btn-label');
            $btn.prop('disabled', true);
            $label.text('Загрузка...');

            const $prog = $('#globalImportProgress');
            $prog.show();
            $prog.find('.import-progress-bar').addClass('indeterminate').css('width', '');
            $prog.find('.import-progress-text').text('Загрузка списка с ' + source + '...');

            $.ajax({
                url: '/profile/import/json',
                type: 'POST',
                data: { nickname: nickname, source: source },
                dataType: 'json'
            })
                .done(function (resp) {
                    showToast(resp.message || 'Импорт запущен', 'success');
                    $prog.find('.import-progress-text').text('Список загружен, постеры в фоне...');

                    $.get('/profile/titles', function (data) {
                        if (Array.isArray(data.titles)) ingestViews(data);
                    });

                    startPosterPolling();
                })
                .fail(function (xhr) {
                    let msg = 'Ошибка импорта';
                    try { msg = JSON.parse(xhr.responseText).message || msg; } catch (_) {}
                    showToast(msg, 'error');
                    $prog.hide();
                })
                .always(function () {
                    $btn.prop('disabled', false);
                    $label.text('Загрузить');
                });
        });

        $('.import-tab').on('click', function () {
            activateTab($(this).data('target'));
            const target = $(this).data('target');

            $('.import-tab').removeClass('active');
            $(this).addClass('active');

            $('.import-tab-pane').removeClass('active');
            $('#pane-' + target).addClass('active');
        });

        $('#changePasswordBtn').on('click', function () {
            Modal.open('#passwordModal');
        });

        $('#deleteAccountBtn').on('click', function () {
            Modal.open('#deleteAccountModal');
        });

        $(window).on('scroll', revealOnScroll);
        $(window).on('resize', revealOnScroll);
    }

    function init() {
        bindEvents();
        revealOnScroll();

        $.get('/profile/titles', function (data) {
            if (Array.isArray(data.titles) && data.titles.length > 0) {
                ingestViews(data);
            }
            if (data.source) {
                activateTab(sourceToTab(data.source));
            }
        });

        $.get('/profile/import/status', function (s) {
            if (!s) return;
            if (s.running) {
                showImportRunning();
                startPosterPolling();
            } else if (s.total > 0 && !s.done) {
                showGlobalProgress(s.ready, s.total);
                startPosterPolling();
            }
        });
    }

    init();
});