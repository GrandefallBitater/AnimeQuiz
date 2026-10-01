// noinspection JSJQueryEfficiency

$(document).ready(function () {
    const logoutModalHtml = `
        <div class="modal-overlay" id="logoutModal">
            <div class="modal-box modal-centered">
                <span class="modal-icon"></span>
                <h3>Выйти из аккаунта?</h3>
                <p>Вы действительно хотите завершить сессию?<br>
                   Мы будем ждать вашего возвращения!</p>
                <div class="modal-actions">
                    <button class="btn-ghost" id="cancelLogoutBtn">Отмена</button>
                    <button class="btn-danger" id="confirmLogoutBtn">Выйти</button>
                </div>
            </div>
        </div>
    `;

    if (!$('#logoutModal').length) {
        $('body').append(logoutModalHtml);
    }

    const $logoutModal = $('#logoutModal');

    window.openLogoutModal = function () {
        $logoutModal.addClass('open');
        $('body').css('overflow', 'hidden');
    };

    function closeLogoutModal() {
        $logoutModal.removeClass('open');
        $('body').css('overflow', '');
    }

    $('#cancelLogoutBtn').on('click', closeLogoutModal);

    $logoutModal.on('click', function (e) {
        if (e.target === this) closeLogoutModal();
    });

    $(document).on('keydown', function (e) {
        if (e.key === 'Escape') closeLogoutModal();
    });

    $('#confirmLogoutBtn').on('click', function () {
        const $form = $('<form>', {
            method: 'POST',
            action: '/logout'
        });

        const currentToken = $('meta[name="_csrf"]').attr('content');

        if (currentToken) {
            $form.append($('<input>', {
                type: 'hidden',
                name: '_csrf',
                value: currentToken
            }));
        } else {
            console.warn("CSRF token missing! Проверьте наличие мета-тегов _csrf в <head> страницы.");
        }

        $('body').append($form);
        $form.submit();
    });
});