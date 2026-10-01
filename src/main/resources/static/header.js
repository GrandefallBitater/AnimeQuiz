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

    const setAvatarModalHtml = `
<div class="modal-overlay" id="setAvatarModal">
    <div class="modal-box modal-centered">
        <div class="modal-header">
            <h3>Изменение аватарки</h3>
            <button class="modal-close" id="closeSetAvatarModal" type="button" data-close>✕</button>
        </div>

        <form id="setAvatarForm">
            <div class="form-group">
                <label for="favatar">Файл изображения</label>
                <input type="file" id="favatar" name="avatar" accept="image/*">
                <span class="form-hint">PNG, JPG или WEBP. Не больше 5 МБ.</span>
            </div>

            <div class="form-group" style="text-align:center;">
                <img id="avatarPreview" alt="Превью"
                     style="display:none; max-width:140px; max-height:140px;
                            border-radius:50%; object-fit:cover;
                            border:2px solid rgba(168,85,247,0.5);
                            box-shadow:0 0 25px rgba(124,58,237,0.35);">
            </div>

            <div class="modal-actions">
                <button type="button" class="btn-ghost" id="cancelSetAvatarBtn">Отмена</button>
                <button type="submit" class="btn-main" id="confirmSetAvatarBtn">Сохранить</button>
            </div>
        </form>
    </div>
</div>
`;

    if (!$('#setAvatarModal').length) {
        $('body').append(setAvatarModalHtml);
    }

    const $setAvatarModal = $('#setAvatarModal');

    window.openSetAvatarModal = function () {
        $setAvatarModal.addClass('open');
        $('body').css('overflow', 'hidden');
    };

    function closeSetAvatarModal() {
        $setAvatarModal.removeClass('open');
        $('body').css('overflow', '');
        $('#setAvatarForm')[0].reset();
        $('#avatarPreview').hide().attr('src', '');
    }

    $('#cancelSetAvatarBtn, #closeSetAvatarModal').on('click', closeSetAvatarModal);

    $setAvatarModal.on('click', function (e) {
        if (e.target === this) closeSetAvatarModal();
    });

    $(document).on('keydown', function (e) {
        if (e.key === 'Escape') closeSetAvatarModal();
    });

    $('#favatar').on('change', function () {
        const file = this.files && this.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = function (ev) {
            $('#avatarPreview').attr('src', ev.target.result).show();
        };
        reader.readAsDataURL(file);
    });

    $('#setAvatarForm').on('submit', function (e) {
        e.preventDefault();

        const fileInput = $('#favatar')[0];
        if (!fileInput.files || !fileInput.files[0]) {
            showToast('Выберите файл изображения', 'error');
            return;
        }

        const csrfToken  = $('meta[name="_csrf"]').attr('content');
        const csrfHeader = $('meta[name="_csrf_header"]').attr('content');

        const formData = new FormData();
        formData.append('avatar', fileInput.files[0]);

        $.ajax({
            url: '/profile/avatar/json',
            type: 'POST',
            data: formData,
            processData: false,
            contentType: false,
            dataType: 'json',
            headers: (csrfToken && csrfHeader) ? { [csrfHeader]: csrfToken } : {}
        })
            .done(function (response) {
                showToast(response.message || 'Аватарка обновлена', 'success');
                closeSetAvatarModal();
                setTimeout(function () {
                    window.location.reload();
                }, 500);
            })
            .fail(function (xhr) {
                console.error(xhr);
                const msg = xhr.responseJSON && xhr.responseJSON.message
                    ? xhr.responseJSON.message
                    : 'Ошибка загрузки аватарки';
                closeSetAvatarModal();
                showToast(msg, 'error');
            });
    });
});