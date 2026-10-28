$(document).ready(function () {
    function showToast(message, type = 'success') {
        const $container = $('#toastContainer');
        const $toast = $('<div>')
            .addClass(`toast toast-${type}`)
            .text(message == null ? '' : String(message));

        $container.append($toast);

        requestAnimationFrame(function () {
            $toast.addClass('show');
        });

        setTimeout(function () {
            $toast.removeClass('show');
            setTimeout(function () {
                $toast.remove();
            }, 400);
        }, 3500);
    }

    window.showToast = showToast;
});