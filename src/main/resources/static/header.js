// noinspection JSJQueryEfficiency

$(document).ready(function () {
    const setCustomNamemodalHtml = `
<div class="modal-overlay" id="setCustomNamemodal">
            <div class="modal-box modal-centered">
         <div class="modal-header">
                <h3 id="setCustomNameModalTitle">Изменение имени</h3>
                <button class="modal-close" id="closeSetCustomNameModal" type="button" data-close>✕</button>
         </div>
    
            <form id="setCustomNameForm">
                <div class="form-group">
                    <input type="text" id="fname" placeholder="Например: DojoSatoru">
                </div>
                <div class="modal-actions">
                    <button type="button" class="btn-ghost" id="cancelSetCustomNameBtn">Отмена</button>
                    <button type="submit" class="btn-main" id="confrimSetCustomNameBtn">Создать</button>
                </div>
            </form>
        </div>
</div>
    `;

    if (!$('#setCustomNamemodal').length) {
        $('body').append(setCustomNamemodalHtml);
    }

    const $setCustomNamemodal = $('#setCustomNamemodal');

    window.openSetCustomNamemodal = function () {
        $setCustomNamemodal.addClass('open');
        $('body').css('overflow', 'hidden');
    };

    function closeSetCustomNamemodal() {
        $setCustomNamemodal.removeClass('open');
        $('body').css('overflow', '');
    }

    $('#cancelSetCustomNameBtn').on('click', closeSetCustomNamemodal);

    $setCustomNamemodal.on('click', function (e) {
        if (e.target === this) closeSetCustomNamemodal();
    });

    $(document).on('keydown', function (e) {
        if (e.key === 'Escape') closeSetCustomNamemodal();
    });

    $('#confrimSetCustomNameBtn').on('click', function (e) {
        e.preventDefault();

        const csrfToken = $('meta[name="_csrf"]').attr('content');
        const csrfHeader = $('meta[name="_csrf_header"]').attr('content');

        if (csrfToken && csrfHeader) {
            const headers = {};
            headers[csrfHeader] = csrfToken;
            $.ajaxSetup({ headers: headers });
        }

        $.ajax({
            url: '/setCustomName',
            type: 'POST',
            data: {
                userName: $('#fname').val(),
            },
            dataType: 'json',
        })
            .done(function (response) {
                showToast(response.message, 'success');
                $('#fname').val("");
                closeSetCustomNamemodal();
                setTimeout(function () {
                    window.location.reload();
                }, 500);
            })
            .fail(function (xhr) {
                console.error(xhr);
                if (xhr.responseJSON && xhr.responseJSON.message) {
                    $('#fname').val("");
                    closeSetCustomNamemodal();
                    showToast(xhr.responseJSON.message, 'error');
                } else {
                    showToast('Ошибка запроса', 'error');
                }
            });
    });

    //TODO create setAvatarModal
});