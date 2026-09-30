$(document).ready(function () {
    const roleNames = { ADMIN: 'Администратор', USER: 'Пользователь' };
    const statusNames = { enabled: 'Включён', disabled: 'Отключён' };
    const defaultLogoUrl = '/server-images/anime-logo.png';

    let users = [];
    let editingId = null;
    let pendingDeleteId = null;
    let searchQuery = '';

    const $tbody = $('#usersTableBody');
    const $usersCount = $('#usersCount');
    const $searchInput = $('#userSearch');
    const $userModal = $('#userModal');
    const $userModalTitle = $('#userModalTitle');
    const $userForm = $('#userForm');
    const $passRow = $('#passRow');
    const $fNick = $('#fNick');
    const $fPass = $('#fPass');
    const $fRole = $('#fRole');
    const $fStatus = $('#fStatus');
    const $submitUserBtn = $('#submitUserBtn');
    const $deleteModal = $('#deleteModal');
    const $deleteUserName = $('#deleteUserName');

    const $adminNameField =$('#adminDisplayName');
    const $adminAvatarField =$('#adminAvatar');
    const $adminRoleBadge = $('#adminRoleBadge');
    const $adminStatusField = $('#adminStatusField');

    const csrfToken = $('meta[name="_csrf"]').attr('content');
    const csrfHeader = $('meta[name="_csrf_header"]').attr('content');

    if (csrfToken && csrfHeader) {
        const headers = {};
        headers[csrfHeader] = csrfToken;
        $.ajaxSetup({ headers: headers });
    }

    function loadAdminInfo() {
        $.ajax({
            url: '/getAdminInfo',
            type: 'GET',
            dataType: 'json'
        }).done(function (admin) {
            if (admin.authenticated) {
                if ($adminNameField.length) {
                    $adminNameField.text(admin.displayName);
                }

                if ($adminRoleBadge.length && admin.role) {
                    const readableRole = roleNames[admin.role] || admin.role;
                    $adminRoleBadge.text(readableRole);
                    $adminRoleBadge.attr('class', 'role-badge role-' + admin.role.toLowerCase());
                }

                if ($adminStatusField.length && admin.enabled !== undefined) {
                    const statusKey = admin.enabled ? 'enabled' : 'disabled';
                    const readableStatus = statusNames[statusKey];
                    $adminStatusField.html('<span class="status-dot"></span>' + readableStatus);
                    $adminStatusField.attr('class', 'status-badge status-' + statusKey);
                }

                if ($adminAvatarField.length) {
                    if ($adminAvatarField.is('img')) {
                        $adminAvatarField.attr('src', admin.avatarUrl);
                    } else {
                        $adminAvatarField.text('');
                        $adminAvatarField.css('background-image', 'url(' + admin.avatarUrl + ')');
                        $adminAvatarField.css({
                            'background-size': 'cover',
                            'background-position': 'center',
                            'background-repeat': 'no-repeat'
                        });
                    }
                }
            }
        }).fail(function (xhr) {
            console.error('Не удалось загрузить данные администратора:', xhr);
        });
    }

    function loadUsers() {
        return $.ajax({
            url: '/getUsers',
            type: 'GET',
            dataType: 'json'
        })
            .then(function (data) {
                users = data.map(user => ({
                    id: user.username,
                    nick: user.displayName || user.username,
                    role: user.role.toUpperCase() || 'USER',
                    status: user.enabled ? 'enabled' : 'disabled',
                    avatar: user.avatarUrl || defaultLogoUrl
                }));
                renderUsers(users);
            })
            .fail(function (xhr) {
                console.error('Ошибка загрузки пользователей:', xhr);
                showToast('Не удалось загрузить список пользователей', 'error');
                renderUsers([]);
            });
    }

    function renderUsers(list) {
        $usersCount.text(users.length);

        if (!list.length) {
            $tbody.html('<tr><td colspan="4" class="empty-row">😔 Ничего не найдено</td></tr>');
            return;
        }

        const rows = list.map(u => `
            <tr>
                <td>
                    <div class="table-user">
                        <div class="table-avatar">
                            <img src="${u.avatar}" 
                                 alt="avatar" 
                                 style="width: 100%; height: 100%; object-fit: cover; border-radius: 50%;" 
                                 onerror="this.onerror=null; this.src='${defaultLogoUrl}';" />
                        </div>
                        <div>
                            <div class="table-nick">${u.nick}</div>
                            <div class="table-id">${u.id}</div>
                        </div>
                    </div>
                </td>
                <td><span class="role-badge role-${u.role}">${roleNames[u.role]}</span></td>
                <td>
                    <span class="status-badge status-${u.status}">
                        <span class="status-dot"></span>${statusNames[u.status]}
                    </span>
                </td>
                <td class="td-actions">
                    <button class="icon-btn edit-btn"   data-action="edit"   data-id="${u.id}" title="Редактировать">✏️</button>
                    <button class="icon-btn delete-btn" data-action="delete" data-id="${u.id}" title="Удалить">🗑️</button>
                </td>
            </tr>
        `).join('');

        $tbody.html(rows);
    }

    function applyFilter() {
        const q = searchQuery.trim().toLowerCase();
        const list = users.filter(u => u.nick.toLowerCase().includes(q));
        renderUsers(list);
    }

    $searchInput.on('input', function () {
        searchQuery = $(this).val();
        applyFilter();
    });

    function openModal($modal) {
        $modal.addClass('open');
        $('body').css('overflow', 'hidden');
    }

    function closeModal($modal) {
        $modal.removeClass('open');
        $('body').css('overflow', '');
    }

    function openCreateModal() {
        editingId = null;
        $userForm[0].reset();
        $passRow.show();
        $userModalTitle.text('Создание пользователя');
        $submitUserBtn.text('Создать');
        openModal($userModal);
    }

    function openEditModal(id) {
        const u = users.find(x => x.id === id);
        if (!u) return;
        editingId = id;
        $passRow.hide();
        $fNick.val(u.nick);
        $fRole.val(u.role);
        $fStatus.val(u.status);
        $userModalTitle.text('Редактирование пользователя');
        $submitUserBtn.text('Сохранить изменения');
        openModal($userModal);
    }

    function openDeleteModal(id) {
        const u = users.find(x => x.id === id);
        if (!u) return;
        pendingDeleteId = id;
        $deleteUserName.text(u.nick);
        openModal($deleteModal);
    }

    $tbody.on('click', '[data-action]', function () {
        const action = $(this).data('action');
        const id = String($(this).data('id'));

        if (action === 'edit') openEditModal(id);
        if (action === 'delete') openDeleteModal(id);
    });

    $('#createUserBtn').on('click', openCreateModal);

    $userForm.on('submit', function (e) {
        e.preventDefault();

        const nick = $fNick.val().trim();
        const pass = $fPass.val().trim();
        const role = $fRole.val().trim();
        const statusValue = $fStatus.val().trim();
        const enabled = statusValue === 'enabled';

        if (!nick) {
            showToast('Заполните никнейм', 'error');
            return;
        }

        if (editingId === null && pass.length < 6) {
            showToast('Пароль должен быть не короче 6 символов', 'error');
            return;
        }

        if (editingId === null) {
            $.ajax({
                url: '/createUser',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({username: nick, displayName: nick, role: role, enabled: enabled, password: pass}),
                dataType: 'json'
            })
                .done(function (response) {
                    showToast(response.message, 'success');
                    loadUsers()
                })
                .fail(function (xhr) {
                    console.error(xhr);
                    showToast('Ошибка запроса', 'error');
                });
        } else {
            $.ajax({
                url: '/editUser',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({username: editingId, displayName: nick, role: role, enabled: enabled, password: "no"}),
                dataType: 'json'
            })
                .done(function (response) {
                    showToast(response.message, 'success');
                    if (response.isSelfUpdate === true) {
                        showToast('Ваш профиль обновлён. Перенаправление...', 'info');
                        setTimeout(function() {
                            window.location.href = '/profile';
                        }, 1200);
                        return;
                    }
                    loadUsers();
                })
                .fail(function (xhr) {
                    console.error(xhr);
                    showToast('Ошибка запроса', 'error');
                });
        }

        closeModal($userModal);
        applyFilter();
    });

    $('#confirmDeleteBtn').on('click', function () {
        const u = users.find(x => x.id === pendingDeleteId);
        const name = u.id;

        $.ajax({
            url: '/deleteUser',
            type: 'DELETE',
            contentType: 'application/json',
            data: JSON.stringify({ username: name }),
            dataType: 'json'
        })
            .done(function (response) {
                showToast(response.message, 'success');
                loadUsers()
            })
            .fail(function (xhr) {
                console.error(xhr);
                showToast('Ошибка запроса', 'error');
            });
        pendingDeleteId = null;
        closeModal($deleteModal);
        applyFilter();
    });

    $('#closeUserModal').on('click', function () { closeModal($userModal); });
    $('#cancelUserBtn').on('click', function () { closeModal($userModal); });
    $('#cancelDeleteBtn').on('click', function () { closeModal($deleteModal); });

    $userModal.on('click', function (e) {
        if (e.target === this) closeModal($userModal);
    });
    $deleteModal.on('click', function (e) {
        if (e.target === this) closeModal($deleteModal);
    });

    $(document).on('keydown', function (e) {
        if (e.key === 'Escape') {
            closeModal($userModal);
            closeModal($deleteModal);
        }
    });

    const $shikimoriBtn = $('#shikimoriBtn');
    const $lastSync = $('#lastSync');

    $shikimoriBtn.on('click', function () {
        const $btn = $(this);
        if ($btn.prop('disabled')) return;

        showToast("выполняется импорт, пожалуйста, подождите")
        $btn.prop('disabled', true);
        $btn.html('<span class="spinner"></span> Загрузка...');

        $.ajax({
            url: '/ImportSiteAnimeList',
            type: 'POST'
        })
            .done(function (response) {
                $btn.addClass('success');
                $lastSync.text(new Date().toLocaleString('ru-RU'));
                showToast(response.message, 'success');
            })
            .fail(function (xhr) {
                console.error(xhr);
                showToast('Ошибка запроса', 'error');
            })
            .always(function () {
                $btn.prop('disabled', false);
                $btn.html('<span class="btn-icon"></span><span class="btn-label">Загрузить</span>');
                updateLastSync();
            });
    });

    function updateLastSync() {
        $.ajax({
            url: '/getUploadDate',
            type: 'GET'
        })
            .done(function (response) {
                $('#lastSync').text(response.message);
            })
            .fail(function (xhr) {
                console.error(xhr);
                showToast('Ошибка запроса', 'error');
            })
    }

    function revealOnScroll() {
        $('.reveal-on-scroll').each(function () {
            const windowHeight = window.innerHeight;
            const elementTop = this.getBoundingClientRect().top;
            const elementVisible = 100;

            if (elementTop < windowHeight - elementVisible) {
                $(this).addClass('visible');
            }
        });
    }

    $(window).on('scroll', revealOnScroll);

    loadAdminInfo();
    updateLastSync();
    loadUsers().then(function () {
        revealOnScroll();
    });
});