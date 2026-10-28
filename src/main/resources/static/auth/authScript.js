
// noinspection JSJQueryEfficiency

$(document).ready(function () {
    const csrfToken = $('meta[name="_csrf"]').attr('content');
    const csrfHeader = $('meta[name="_csrf_header"]').attr('content');

    if (csrfToken && csrfHeader) {
        const headers = {};
        headers[csrfHeader] = csrfToken;
        $.ajaxSetup({ headers: headers });
    }

    const $loginForm = $('#loginForm');
    const $loginBtn = $('#loginBtn');

    if ($loginForm.length) {
        $loginForm.on('submit', function () {
            const username = $('#username').val().trim();
            const password = $('#password').val().trim();

            if (!username || !password) {
                showToast('Заполните все поля', 'error');
                return false;
            }
            $loginBtn.prop('disabled', true);
            $loginBtn.html('<span class="spinner"></span> Входим...');
        });
    }

    const $registerForm = $('#registerForm');
    const $registerBtn = $('#registerBtn');

    if ($registerForm.length) {
        $registerForm.on('submit', function (e) {
            e.preventDefault();

            const username = $('#regUsername').val().trim();
            const password = $('#regPassword').val();
            const passwordConfirm = $('#regPasswordConfirm').val();

            if (!username) {
                showToast('Введите никнейм', 'error');
                return;
            }
            if (password.length < 6) {
                showToast('Пароль должен быть не короче 6 символов', 'error');
                return;
            }
            if (password !== passwordConfirm) {
                showToast('Пароли не совпадают', 'error');
                return;
            }
            $registerBtn.prop('disabled', true);
            $registerBtn.html('<span class="spinner"></span> Создаём...');

            $.ajax({
                url: '/auth/register',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    username: username,
                    password: password
                }),
                dataType: 'json'
            })
                .done(function (response) {
                    $registerBtn.addClass('success');
                    $registerBtn.html('✓ Аккаунт создан!');
                    showToast(response.message, 'success');

                    setTimeout(function () {
                        window.location.href = '/login?message=' +
                            encodeURIComponent(response.message);
                    }, 1500);
                })
                .fail(function (xhr) {
                    let msg = 'Ошибка при регистрации';
                    if (xhr.responseJSON && xhr.responseJSON.message) {
                        msg = xhr.responseJSON.message;
                    }
                    showToast(msg, 'error');
                    $registerBtn.prop('disabled', false);
                    $registerBtn.html('<span>Создать аккаунт</span>');
                });
        });
    }

    $('.auth-alert').each(function () {
        const $alert = $(this);
        setTimeout(function () {
            $alert.css({
                'transition': 'all 0.5s ease',
                'opacity': '0',
                'transform': 'translateY(-10px)'
            });
            setTimeout(function () { $alert.remove(); }, 500);
        }, 5000);
    });

    $('.password-toggle').on('click', function () {
        const $btn = $(this);
        const $input = $btn.closest('.password-wrap').find('.form-input');
        const show = $input.attr('type') === 'password';

        $input.attr('type', show ? 'text' : 'password');
        $btn.toggleClass('is-visible', show)
            .attr('aria-pressed', show ? 'true' : 'false')
            .attr('aria-label', show ? 'Скрыть пароль' : 'Показать пароль')
            .attr('title', show ? 'Скрыть пароль' : 'Показать пароль');
    });
});
