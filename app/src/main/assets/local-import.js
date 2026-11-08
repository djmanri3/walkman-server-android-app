(function () {
    if (window.__wmLocalImport) return;
    window.__wmLocalImport = true;
    function finish(message) {
        if (typeof window.hideLocalImportBanner === 'function') window.hideLocalImportBanner();
        var banner = document.getElementById('local-banner');
        if (banner) banner.style.display = 'none';
        var status = document.getElementById('local-status');
        if (status) {
            status.textContent = message || '';
            status.style.display = message ? 'block' : 'none';
        }
    }
    window.onAndroidLocalError = function (message) {
        finish(message || 'No se pudo cargar la carpeta de música. Inténtalo de nuevo.');
    };
    window.onAndroidLocalCancelled = function () { finish(''); };
    var receive = window.onAndroidLocalTracks;
    window.onAndroidLocalTracks = function (tracks) {
        if (!Array.isArray(tracks) || !tracks.some(function (t) { return t.Type === 'Audio'; })) {
            finish('No se encontraron archivos de audio en la carpeta.');
            return;
        }
        try {
            if (typeof receive !== 'function') throw new Error('Missing local music callback');
            receive.apply(this, arguments);
            finish('');
        } catch (error) {
            console.error('No se pudo completar la importación local', error);
            finish('No se pudo cargar la biblioteca local. Vuelve a seleccionar la carpeta.');
        }
    };
})();
