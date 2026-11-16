(function () {
    const root = document.documentElement;

    function syncAnimations() {
        if (document.hidden) {
            root.classList.add('animations-paused');
        } else {
            root.classList.remove('animations-paused');
        }
    }

    document.addEventListener('visibilitychange', syncAnimations);
    syncAnimations();
})();
