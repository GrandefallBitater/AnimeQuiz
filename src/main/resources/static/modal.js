// noinspection JSUnusedGlobalSymbols

/**
 * modal.js — универсальное управление модальными окнами на jQuery.
 *
 * Возможности:
 *  - открытие по [data-modal-open="#id"]
 *  - закрытие по [data-close] внутри модалки
 *  - закрытие по клику на overlay
 *  - закрытие по Esc
 *  - блокировка скролла body
 *  - API: Modal.open('#id'), Modal.close(), Modal.closeAll(),
 *         Modal.alert({...}), Modal.confirm({...}) -> Promise<boolean>
 *  - события 'modal:open' / 'modal:close' на элементе модалки
 */
(function ($, window, document) {
    'use strict';

    const OPEN_CLASS = 'open';
    const BODY_LOCK_CLASS = 'modal-open';

    const openStack = [];

    function lockBody() {
        $('body').addClass(BODY_LOCK_CLASS).css('overflow', 'hidden');
    }

    function unlockBody() {
        $('body').removeClass(BODY_LOCK_CLASS).css('overflow', '');
    }

    function fire($el, name) {
        $el.trigger(name);
    }

    const Modal = {

        open(target) {
            const $el = $(target).first();
            if (!$el.length || !$el.hasClass('modal-overlay')) return;
            if (openStack.indexOf($el[0]) !== -1) return;

            $el.addClass(OPEN_CLASS);
            openStack.push($el[0]);
            lockBody();

            const $auto = $el.find('[data-autofocus]').first();
            const $field = $auto.length
                ? $auto
                : $el.find('input:not([type="hidden"]), textarea, select').first();

            if ($field.length) {
                setTimeout(() => $field.trigger('focus'), 80);
            }

            fire($el, 'modal:open');
        },

        close(target) {
            const $el = target
                ? $(target).first()
                : $(openStack[openStack.length - 1] || []);

            if (!$el.length) return;

            $el.removeClass(OPEN_CLASS);

            const idx = openStack.indexOf($el[0]);
            if (idx !== -1) openStack.splice(idx, 1);

            if (openStack.length === 0) unlockBody();

            fire($el, 'modal:close');
        },

        closeAll() {
            openStack.slice().forEach(el => Modal.close(el));
            openStack.length = 0;
            unlockBody();
        },

        /**
         * Простое информационное окно.
         * Modal.alert({ icon, title, text, buttonText, size })
         */
        alert(opts) {
            const o = $.extend({
                id: 'modalAlertDynamic',
                icon: '',
                title: '',
                text: '',
                buttonText: 'OK',
                size: 'modal-sm'
            }, opts || {});

            $('#' + o.id).remove();

            const $el = $(`
                <div class="modal-overlay" id="${o.id}">
                    <div class="modal-box ${o.size} modal-centered">
                        <span class="modal-icon">${o.icon}</span>
                        <h3>${o.title}</h3>
                        <p>${o.text}</p>
                        <div class="modal-actions modal-actions-center">
                            <button class="btn-main" data-close>${o.buttonText}</button>
                        </div>
                    </div>
                </div>
            `).appendTo('body');

            $el.one('modal:close', () => $el.remove());
            Modal.open($el);
        },

        /**
         * Подтверждение. Возвращает Promise<boolean>.
         * Modal.confirm({ title, text, confirmText, cancelText, danger })
         *   .then(ok => { if (ok) ... });
         */
        confirm(opts) {
            const o = $.extend({
                id: 'modalConfirmDynamic',
                icon: '',
                title: 'Подтвердите действие',
                text: '',
                confirmText: 'Подтвердить',
                cancelText: 'Отмена',
                danger: false,
                size: 'modal-sm'
            }, opts || {});

            return new Promise(resolve => {
                $('#' + o.id).remove();

                const confirmClass = o.danger ? 'btn-danger' : 'btn-main';
                const $el = $(`
                    <div class="modal-overlay" id="${o.id}">
                        <div class="modal-box ${o.size} modal-centered">
                            <span class="modal-icon">${o.icon}</span>
                            <h3>${o.title}</h3>
                            <p>${o.text}</p>
                            <div class="modal-actions modal-actions-center">
                                <button class="btn-ghost" data-cancel>${o.cancelText}</button>
                                <button class="${confirmClass}" data-confirm>${o.confirmText}</button>
                            </div>
                        </div>
                    </div>
                `).appendTo('body');

                let resolved = false;
                const finish = (result) => {
                    if (resolved) return;
                    resolved = true;
                    Modal.close($el);
                    $el.remove();
                    resolve(result);
                };

                $el.on('click', '[data-confirm]', () => finish(true));
                $el.on('click', '[data-cancel]',  () => finish(false));
                $el.one('modal:close', () => finish(false));

                Modal.open($el);
            });
        }
    };

    $(document).on('click', '[data-modal-open]', function (e) {
        e.preventDefault();
        Modal.open($(this).attr('data-modal-open'));
    });

    $(document).on('click', '[data-close]', function (e) {
        e.preventDefault();
        const $modal = $(this).closest('.modal-overlay');
        if ($modal.length) Modal.close($modal);
    });

    $(document).on('mousedown', '.modal-overlay', function (e) {
        const $overlay = $(this);
        if (e.target !== this) return;
        $overlay.one('mouseup', function (ev) {
            if (ev.target === this) Modal.close($overlay);
        });
    });

    $(document).on('keydown', function (e) {
        if (e.key !== 'Escape') return;
        if (!openStack.length) return;
        Modal.close();
    });

    window.Modal = Modal;

})(jQuery, window, document);